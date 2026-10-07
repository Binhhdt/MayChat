-- =====================================================================
-- MayChat - migration 30: end-to-end encryption for one-to-one chats
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS only: one new table, one new column, two new functions and one new
-- check that only concerns conversations whose encryption is switched ON.
-- Conversations that do not use encryption behave exactly as before.
--
-- What the server keeps: the PUBLIC half of each phone's key (useless for
-- reading messages) and whether a conversation has encryption on. The
-- private half never leaves the phone, so the server cannot read the
-- encrypted messages.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. PUBLIC KEYS (one per account: the phone currently in use)
-- ---------------------------------------------------------------------

create table if not exists public.e2e_keys (
    user_id     uuid primary key references public.profiles (id) on delete cascade,
    public_key  text not null check (char_length(public_key) between 40 and 600),
    updated_at  timestamptz not null default now()
);

alter table public.e2e_keys enable row level security;
revoke all on public.e2e_keys from anon, authenticated;
grant select on public.e2e_keys to authenticated;

drop policy if exists "e2e_keys: logged-in users can read" on public.e2e_keys;
create policy "e2e_keys: logged-in users can read"
    on public.e2e_keys for select to authenticated
    using (true);

-- Saves the public key of MY phone (replaces the one of an earlier phone).
create or replace function public.set_my_e2e_key(p_public_key text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_public_key is null or char_length(p_public_key) < 40 or char_length(p_public_key) > 600 then
        raise exception 'invalid_key';
    end if;
    insert into public.e2e_keys (user_id, public_key)
    values (v_me, p_public_key)
    on conflict (user_id) do update
        set public_key = excluded.public_key, updated_at = now()
        where public.e2e_keys.public_key is distinct from excluded.public_key;
end;
$$;


-- ---------------------------------------------------------------------
-- 2. THE SWITCH OF A CONVERSATION
-- ---------------------------------------------------------------------

alter table public.conversations add column if not exists e2e boolean not null default false;

-- Switches encryption on or off for one conversation (either of the two
-- people may do it). Switching on needs both phones to have a key, i.e.
-- both have opened a version of the app that knows encryption.
create or replace function public.set_conversation_e2e(p_conversation uuid, p_on boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_other uuid;
    v_now   boolean;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select case when c.user_a = v_me then c.user_b else c.user_a end, c.e2e
    into v_other, v_now
    from public.conversations c
    where c.id = p_conversation and (c.user_a = v_me or c.user_b = v_me);
    if not found then raise exception 'not_allowed'; end if;
    if v_now = coalesce(p_on, false) then return; end if;

    if p_on then
        if not exists (select 1 from public.e2e_keys where user_id = v_me) then
            raise exception 'e2e_no_key';
        end if;
        if not exists (select 1 from public.e2e_keys where user_id = v_other) then
            raise exception 'e2e_peer_not_ready';
        end if;
    end if;

    update public.conversations set e2e = coalesce(p_on, false) where id = p_conversation;

    -- A notice in the chat, like the one for a changed background.
    insert into public.messages (conversation_id, sender_id, content, kind)
    values (
        p_conversation, v_me,
        case when p_on then 'đã bật mã hóa đầu cuối' else 'đã tắt mã hóa đầu cuối' end,
        'system'
    );
end;
$$;

revoke all on function public.set_my_e2e_key(text) from public, anon;
revoke all on function public.set_conversation_e2e(uuid, boolean) from public, anon;
grant execute on function public.set_my_e2e_key(text) to authenticated;
grant execute on function public.set_conversation_e2e(uuid, boolean) to authenticated;


-- ---------------------------------------------------------------------
-- 3. NO READABLE TEXT IN AN ENCRYPTED CONVERSATION
-- While encryption is on, a text message must arrive encrypted (it then
-- starts with "e2e:1:"). This stops a phone that has not noticed the
-- switch yet from sending readable text; the app then encrypts and sends
-- again by itself. Conversations without encryption are not affected.
-- ---------------------------------------------------------------------

create or replace function public.require_e2e_text()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    -- (The line a call writes into the chat, "📞 Cuộc gọi ...", stays
    -- readable: the server needs it to make the other phone ring.)
    if new.kind = 'text' and new.content not like 'e2e:1:%' and new.content not like '📞%' then
        if exists (select 1 from public.conversations c where c.id = new.conversation_id and c.e2e) then
            raise exception 'e2e_required';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists messages_require_e2e on public.messages;
create trigger messages_require_e2e
    before insert on public.messages
    for each row execute function public.require_e2e_text();
