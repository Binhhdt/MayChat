-- =====================================================================
-- MayChat - migration 10: reply to a message (quote) and reactions
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS: three columns on messages, one table, two functions, one realtime
-- entry. CHANGES one existing function: recall_message (from migration 09)
-- now also blanks the quoted text in replies and removes the reactions of
-- the recalled message. Nothing else is touched.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. REPLY (QUOTE): what a message is answering
-- ---------------------------------------------------------------------

-- The message being answered. If that message is ever deleted, the link
-- simply becomes empty.
alter table public.messages
    add column if not exists reply_to_id uuid references public.messages (id) on delete set null;

-- A short copy of the quoted text and who wrote it, saved with the reply,
-- so the quote can be shown without loading the old message again.
alter table public.messages add column if not exists reply_preview text;
alter table public.messages add column if not exists reply_sender_id uuid;

do $$
begin
    alter table public.messages add constraint messages_reply_preview_check
        check (reply_preview is null or char_length(reply_preview) <= 200);
exception
    when duplicate_object then null;
end;
$$;

-- The app may fill in these three columns when sending.
grant insert (reply_to_id, reply_preview, reply_sender_id) on public.messages to authenticated;


-- ---------------------------------------------------------------------
-- 2. REACTIONS: one reaction per person per message
-- ---------------------------------------------------------------------

create table if not exists public.message_reactions (
    message_id  uuid not null references public.messages (id) on delete cascade,
    user_id     uuid not null references public.profiles (id) on delete cascade,
    emoji       text not null check (char_length(emoji) between 1 and 16),
    created_at  timestamptz not null default now(),
    primary key (message_id, user_id)
);

alter table public.message_reactions enable row level security;
revoke all on public.message_reactions from anon, authenticated;
grant select on public.message_reactions to authenticated;

-- Only the two members of the conversation can see its reactions.
drop policy if exists "reactions: members can read" on public.message_reactions;
create policy "reactions: members can read"
    on public.message_reactions for select
    to authenticated
    using (
        exists (
            select 1
            from public.messages m
            join public.conversations c on c.id = m.conversation_id
            where m.id = message_reactions.message_id
              and (select auth.uid()) in (c.user_a, c.user_b)
        )
    );

-- Sets, changes or removes MY reaction on a message.
-- p_emoji empty or null = remove my reaction.
create or replace function public.set_reaction(p_message uuid, p_emoji text)
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
        select 1
        from public.messages m
        join public.conversations c on c.id = m.conversation_id
        where m.id = p_message
          and v_me in (c.user_a, c.user_b)
          and m.recalled_at is null
    ) then
        raise exception 'not_allowed';
    end if;

    if p_emoji is null or p_emoji = '' then
        delete from public.message_reactions
        where message_id = p_message and user_id = v_me;
    else
        insert into public.message_reactions (message_id, user_id, emoji)
        values (p_message, v_me, left(p_emoji, 16))
        on conflict (message_id, user_id) do update
            set emoji = excluded.emoji,
                created_at = now();
    end if;
end;
$$;

-- All reactions in one conversation I am a member of.
create or replace function public.conversation_reactions(p_conversation uuid)
returns table (message_id uuid, user_id uuid, emoji text)
language sql
stable
security definer
set search_path = ''
as $$
    select r.message_id, r.user_id, r.emoji
    from public.message_reactions r
    join public.messages m on m.id = r.message_id
    join public.conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation
      and auth.uid() in (c.user_a, c.user_b);
$$;

revoke execute on function public.set_reaction(uuid, text)           from public, anon;
revoke execute on function public.conversation_reactions(uuid)       from public, anon;
grant execute on function public.set_reaction(uuid, text)            to authenticated;
grant execute on function public.conversation_reactions(uuid)        to authenticated;

-- Lets a reaction show up on the other phone without refreshing.
do $$
begin
    alter publication supabase_realtime add table public.message_reactions;
exception
    when duplicate_object then null;
end;
$$;


-- ---------------------------------------------------------------------
-- 3. RECALL: same as in migration 09, plus two clean-up steps
-- ---------------------------------------------------------------------

create or replace function public.recall_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me  uuid := auth.uid();
    v_msg public.messages%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    select * into v_msg from public.messages where id = p_message;
    if not found or v_msg.sender_id <> v_me then
        raise exception 'not_allowed';
    end if;
    if v_msg.recalled_at is not null then return; end if;

    update public.messages
    set content = 'Tin nhắn đã được thu hồi',
        kind = 'text',
        media_path = null,
        duration_ms = null,
        recalled_at = now()
    where id = p_message;

    -- If it was the newest message, fix the preview in the conversation list.
    update public.conversations
    set last_message_text = 'Tin nhắn đã được thu hồi'
    where id = v_msg.conversation_id
      and last_sender_id = v_msg.sender_id
      and last_message_at = v_msg.created_at;

    -- NEW: replies that quote this message must not keep its text.
    update public.messages
    set reply_preview = 'Tin nhắn đã được thu hồi'
    where reply_to_id = p_message;

    -- NEW: a recalled message has no reactions.
    delete from public.message_reactions where message_id = p_message;
end;
$$;
