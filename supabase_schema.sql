-- =====================================================================
-- MayChat - Supabase schema for MILESTONE 1
-- Accounts + find users + one-to-one realtime text chat + read receipts
--
-- How to use: Supabase Dashboard -> SQL Editor -> New query -> paste ALL
-- of this file -> Run. Expected result: "Success. No rows returned".
-- Safe to run again if something went wrong halfway.
--
-- Uses only free-plan features: Auth, Postgres, Realtime.
-- No Storage, no Edge Functions, no extensions that need a paid plan.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. TABLES
-- ---------------------------------------------------------------------

-- Public profile of each account. The id is the same id Supabase Auth
-- gives the user, so one account always has exactly one profile.
create table if not exists public.profiles (
    id            uuid primary key references auth.users (id) on delete cascade,
    username      text not null unique
                  check (username ~ '^[a-z0-9_]{3,20}$'),
    display_name  text not null
                  check (char_length(display_name) between 1 and 50),
    last_seen_at  timestamptz,
    created_at    timestamptz not null default now()
);

-- Makes "username starts with ..." searches fast.
create index if not exists profiles_username_prefix_idx
    on public.profiles (username text_pattern_ops);

-- One row per pair of users. user_a is always the smaller id, so the same
-- two people can never end up with two different conversations.
create table if not exists public.conversations (
    id                 uuid primary key default gen_random_uuid(),
    user_a             uuid not null references public.profiles (id) on delete cascade,
    user_b             uuid not null references public.profiles (id) on delete cascade,
    last_message_text  text,
    last_message_at    timestamptz,
    last_sender_id     uuid,
    created_at         timestamptz not null default now(),
    check (user_a < user_b),
    unique (user_a, user_b)
);

create index if not exists conversations_user_a_idx on public.conversations (user_a);
create index if not exists conversations_user_b_idx on public.conversations (user_b);

create table if not exists public.messages (
    id               uuid primary key default gen_random_uuid(),
    conversation_id  uuid not null references public.conversations (id) on delete cascade,
    sender_id        uuid not null default auth.uid()
                     references public.profiles (id) on delete cascade,
    content          text not null
                     check (char_length(content) between 1 and 4000),
    created_at       timestamptz not null default now(),
    read_at          timestamptz
);

-- Makes "latest 30 messages of this conversation" fast (pagination).
create index if not exists messages_conversation_created_idx
    on public.messages (conversation_id, created_at desc);


-- ---------------------------------------------------------------------
-- 2. FUNCTIONS AND TRIGGERS
-- ---------------------------------------------------------------------

-- Creates the profile automatically when someone registers.
-- The app sends username and display_name together with the sign-up.
-- If the username is invalid or already taken, the whole sign-up fails,
-- so there is never an account without a profile.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_username text := lower(trim(new.raw_user_meta_data ->> 'username'));
    v_name     text := trim(new.raw_user_meta_data ->> 'display_name');
begin
    if v_username is null or v_username !~ '^[a-z0-9_]{3,20}$' then
        raise exception 'invalid_username';
    end if;

    insert into public.profiles (id, username, display_name)
    values (new.id, v_username, coalesce(nullif(v_name, ''), v_username));

    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- Lets the register screen check a username BEFORE creating the account.
create or replace function public.username_available(p_username text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select not exists (
        select 1 from public.profiles where username = lower(trim(p_username))
    );
$$;

-- Returns the conversation between me and another user, creating it the
-- first time. Clients cannot insert into "conversations" directly.
create or replace function public.get_or_create_conversation(p_other_user uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_a  uuid;
    v_b  uuid;
    v_id uuid;
begin
    if v_me is null then
        raise exception 'not_authenticated';
    end if;
    if p_other_user is null or p_other_user = v_me then
        raise exception 'invalid_user';
    end if;
    if not exists (select 1 from public.profiles where id = p_other_user) then
        raise exception 'user_not_found';
    end if;

    v_a := least(v_me, p_other_user);
    v_b := greatest(v_me, p_other_user);

    insert into public.conversations (user_a, user_b)
    values (v_a, v_b)
    on conflict (user_a, user_b) do nothing;

    select id into v_id
    from public.conversations
    where user_a = v_a and user_b = v_b;

    return v_id;
end;
$$;

-- Marks every message the OTHER person sent me in this conversation as read.
-- Clients cannot update "messages" directly, so nobody can edit message text
-- or fake a read receipt for someone else.
create or replace function public.mark_conversation_read(p_conversation uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then
        raise exception 'not_authenticated';
    end if;
    if not exists (
        select 1 from public.conversations
        where id = p_conversation and v_me in (user_a, user_b)
    ) then
        raise exception 'not_a_member';
    end if;

    update public.messages
    set read_at = now()
    where conversation_id = p_conversation
      and sender_id <> v_me
      and read_at is null;
end;
$$;

-- Keeps the "last message" preview of a conversation up to date.
create or replace function public.handle_new_message()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.conversations
    set last_message_text = left(new.content, 120),
        last_message_at   = new.created_at,
        last_sender_id    = new.sender_id
    where id = new.conversation_id;
    return new;
end;
$$;

drop trigger if exists on_message_created on public.messages;
create trigger on_message_created
    after insert on public.messages
    for each row execute function public.handle_new_message();


-- ---------------------------------------------------------------------
-- 3. SECURITY: ROW LEVEL SECURITY (RLS)
-- With RLS on and no matching policy, access is DENIED by default.
-- ---------------------------------------------------------------------

alter table public.profiles      enable row level security;
alter table public.conversations enable row level security;
alter table public.messages      enable row level security;

-- Start from zero permissions, then grant only what the app needs.
revoke all on public.profiles, public.conversations, public.messages
    from anon, authenticated;

-- Logged-in users may read profiles (needed for search) and change only
-- their own display name and last-seen time. Username and id are fixed.
grant select on public.profiles to authenticated;
grant update (display_name, last_seen_at) on public.profiles to authenticated;

-- Logged-in users may read conversations and read/send messages,
-- limited further by the policies below.
grant select on public.conversations to authenticated;
grant select on public.messages to authenticated;
grant insert (conversation_id, content) on public.messages to authenticated;

-- People who are NOT logged in (anon) get no table access at all.

drop policy if exists "profiles: logged-in users can read" on public.profiles;
create policy "profiles: logged-in users can read"
    on public.profiles for select
    to authenticated
    using (true);

drop policy if exists "profiles: update own row only" on public.profiles;
create policy "profiles: update own row only"
    on public.profiles for update
    to authenticated
    using (id = (select auth.uid()))
    with check (id = (select auth.uid()));

drop policy if exists "conversations: members can read" on public.conversations;
create policy "conversations: members can read"
    on public.conversations for select
    to authenticated
    using ((select auth.uid()) in (user_a, user_b));

drop policy if exists "messages: members can read" on public.messages;
create policy "messages: members can read"
    on public.messages for select
    to authenticated
    using (
        exists (
            select 1 from public.conversations c
            where c.id = messages.conversation_id
              and (select auth.uid()) in (c.user_a, c.user_b)
        )
    );

-- sender_id is filled in by the database (default auth.uid()), and this
-- policy double-checks it, so nobody can send a message as someone else.
drop policy if exists "messages: members can send as themselves" on public.messages;
create policy "messages: members can send as themselves"
    on public.messages for insert
    to authenticated
    with check (
        sender_id = (select auth.uid())
        and exists (
            select 1 from public.conversations c
            where c.id = messages.conversation_id
              and (select auth.uid()) in (c.user_a, c.user_b)
        )
    );

-- Function permissions: nothing is callable unless listed here.
revoke execute on function public.handle_new_user()                  from public, anon, authenticated;
revoke execute on function public.handle_new_message()               from public, anon, authenticated;
revoke execute on function public.username_available(text)           from public;
revoke execute on function public.get_or_create_conversation(uuid)   from public, anon;
revoke execute on function public.mark_conversation_read(uuid)       from public, anon;

grant execute on function public.username_available(text)          to anon, authenticated;
grant execute on function public.get_or_create_conversation(uuid)  to authenticated;
grant execute on function public.mark_conversation_read(uuid)      to authenticated;


-- ---------------------------------------------------------------------
-- 4. REALTIME
-- Tells Supabase to push changes of these two tables to connected phones.
-- RLS still applies: a phone only receives rows it is allowed to read.
-- ---------------------------------------------------------------------

do $$
begin
    alter publication supabase_realtime add table public.messages;
exception
    when duplicate_object then null;
end;
$$;

do $$
begin
    alter publication supabase_realtime add table public.conversations;
exception
    when duplicate_object then null;
end;
$$;
