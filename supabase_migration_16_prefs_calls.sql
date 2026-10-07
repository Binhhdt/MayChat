-- =====================================================================
-- MayChat - migration 16: pin / mute / delete a conversation (for me only),
-- message notifications on/off, and the call history
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS three tables and six functions.
-- CHANGES one existing function: handle_push_on_message (migration 06) now
-- skips the notification of a normal message when the receiver muted that
-- conversation or switched message notifications off. Calls still ring.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. MY OWN SETTINGS FOR ONE CONVERSATION (the other person never sees them)
-- ---------------------------------------------------------------------

create table if not exists public.conversation_prefs (
    user_id          uuid not null references public.profiles (id) on delete cascade,
    conversation_id  uuid not null references public.conversations (id) on delete cascade,
    pinned_at        timestamptz,                     -- set = pinned at the top of my list
    muted            boolean not null default false,  -- true = no message notifications
    cleared_at       timestamptz,                     -- I deleted everything up to this moment
    primary key (user_id, conversation_id)
);

alter table public.conversation_prefs enable row level security;
revoke all on public.conversation_prefs from anon, authenticated;
grant select on public.conversation_prefs to authenticated;

drop policy if exists "conversation_prefs: read own" on public.conversation_prefs;
create policy "conversation_prefs: read own"
    on public.conversation_prefs for select
    to authenticated
    using (user_id = (select auth.uid()));

-- p_pinned / p_muted: null = leave as it is.  p_clear: true = delete the
-- conversation on my side (messages up to now are hidden for me only).
create or replace function public.set_conversation_pref(
    p_conversation uuid,
    p_pinned boolean,
    p_muted boolean,
    p_clear boolean
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not exists (
        select 1 from public.conversations c
        where c.id = p_conversation and v_me in (c.user_a, c.user_b)
    ) then
        raise exception 'not_allowed';
    end if;

    insert into public.conversation_prefs (user_id, conversation_id)
    values (v_me, p_conversation)
    on conflict do nothing;

    update public.conversation_prefs
    set pinned_at = case
            when p_pinned is null then pinned_at
            when p_pinned then now()
            else null
        end,
        muted = coalesce(p_muted, muted),
        cleared_at = case when coalesce(p_clear, false) then now() else cleared_at end
    where user_id = v_me and conversation_id = p_conversation;

    -- Deleted messages must not stay counted as unread.
    if coalesce(p_clear, false) then
        update public.messages
        set read_at = now()
        where conversation_id = p_conversation
          and sender_id <> v_me
          and read_at is null;
    end if;
end;
$$;


-- ---------------------------------------------------------------------
-- 2. MY ACCOUNT SETTINGS
-- ---------------------------------------------------------------------

create table if not exists public.user_settings (
    user_id        uuid primary key references public.profiles (id) on delete cascade,
    mute_messages  boolean not null default false   -- true = no message notifications at all
);

alter table public.user_settings enable row level security;
revoke all on public.user_settings from anon, authenticated;
grant select on public.user_settings to authenticated;

drop policy if exists "user_settings: read own" on public.user_settings;
create policy "user_settings: read own"
    on public.user_settings for select
    to authenticated
    using (user_id = (select auth.uid()));

create or replace function public.set_mute_messages(p_muted boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    insert into public.user_settings (user_id, mute_messages)
    values (v_me, coalesce(p_muted, false))
    on conflict (user_id) do update set mute_messages = excluded.mute_messages;
end;
$$;


-- ---------------------------------------------------------------------
-- 3. NOTIFICATIONS RESPECT "MUTE"
-- Same function as in migration 06, with one added check (marked NEW).
-- ---------------------------------------------------------------------

create or replace function public.handle_push_on_message()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_cfg       public.push_config%rowtype;
    v_recipient uuid;
begin
    select case when c.user_a = new.sender_id then c.user_b else c.user_a end
    into v_recipient
    from public.conversations c
    where c.id = new.conversation_id;

    -- No phone registered for the recipient: do not call the function at all.
    if v_recipient is null
       or not exists (select 1 from public.push_tokens where user_id = v_recipient) then
        return new;
    end if;

    -- NEW: a normal message is not announced when the receiver muted this
    -- conversation or switched message notifications off. The chat line of
    -- a call (it starts with the telephone sign) always goes through, so
    -- calls keep ringing.
    if left(new.content, 1) <> '📞' and (
        exists (
            select 1 from public.conversation_prefs p
            where p.user_id = v_recipient
              and p.conversation_id = new.conversation_id
              and p.muted
        )
        or exists (
            select 1 from public.user_settings s
            where s.user_id = v_recipient and s.mute_messages
        )
    ) then
        return new;
    end if;

    select * into v_cfg from public.push_config where id;
    if not found then return new; end if;

    perform net.http_post(
        url := v_cfg.function_url,
        body := jsonb_build_object('message_id', new.id),
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'x-push-secret', v_cfg.secret
        )
    );
    return new;
exception
    when others then
        -- A notification problem must never stop a message from being sent.
        return new;
end;
$$;

revoke execute on function public.handle_push_on_message() from public, anon, authenticated;


-- ---------------------------------------------------------------------
-- 4. CALL HISTORY
-- The caller's app writes one row per call and completes it when the call
-- ends. Both people can read the rows they are part of.
-- ---------------------------------------------------------------------

create table if not exists public.calls (
    id          uuid primary key default gen_random_uuid(),
    caller_id   uuid not null references public.profiles (id) on delete cascade,
    callee_id   uuid not null references public.profiles (id) on delete cascade,
    started_at  timestamptz not null default now(),
    status      text not null default 'ringing'
                check (status in ('ringing', 'answered', 'missed', 'declined', 'cancelled', 'failed')),
    duration_s  integer not null default 0 check (duration_s between 0 and 86400)
);

create index if not exists calls_caller_idx on public.calls (caller_id, started_at desc);
create index if not exists calls_callee_idx on public.calls (callee_id, started_at desc);

alter table public.calls enable row level security;
revoke all on public.calls from anon, authenticated;
grant select on public.calls to authenticated;

drop policy if exists "calls: members can read" on public.calls;
create policy "calls: members can read"
    on public.calls for select
    to authenticated
    using ((select auth.uid()) in (caller_id, callee_id));

create or replace function public.log_call_start(p_callee uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_id uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_callee = v_me
       or not exists (select 1 from public.profiles where id = p_callee) then
        raise exception 'not_allowed';
    end if;

    insert into public.calls (caller_id, callee_id)
    values (v_me, p_callee)
    returning id into v_id;
    return v_id;
end;
$$;

create or replace function public.log_call_end(p_call uuid, p_status text, p_duration integer)
returns void
language sql
security definer
set search_path = ''
as $$
    update public.calls
    set status = p_status,
        duration_s = least(greatest(coalesce(p_duration, 0), 0), 86400)
    where id = p_call
      and caller_id = auth.uid()
      and status = 'ringing'
      and p_status in ('answered', 'missed', 'declined', 'cancelled', 'failed');
$$;


-- ---------------------------------------------------------------------
-- 5. PERMISSIONS
-- ---------------------------------------------------------------------

revoke execute on function public.set_conversation_pref(uuid, boolean, boolean, boolean) from public, anon;
revoke execute on function public.set_mute_messages(boolean)                             from public, anon;
revoke execute on function public.log_call_start(uuid)                                   from public, anon;
revoke execute on function public.log_call_end(uuid, text, integer)                      from public, anon;

grant execute on function public.set_conversation_pref(uuid, boolean, boolean, boolean)  to authenticated;
grant execute on function public.set_mute_messages(boolean)                              to authenticated;
grant execute on function public.log_call_start(uuid)                                    to authenticated;
grant execute on function public.log_call_end(uuid, text, integer)                       to authenticated;
