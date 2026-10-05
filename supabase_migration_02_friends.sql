-- =====================================================================
-- MayChat - migration 02: friend requests, friends list, blocking
--
-- Run AFTER supabase_schema.sql. How: Supabase Dashboard -> SQL Editor ->
-- New query -> paste ALL of this file -> Run.
-- Expected result: "Success. No rows returned". Safe to run again.
--
-- Uses only free-plan features: Postgres and Realtime.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. TABLES
-- ---------------------------------------------------------------------

-- One row per pair of users (user_a is always the smaller id).
-- status 'pending'  = requested_by has asked, the other has not answered yet
-- status 'accepted' = they are friends
-- Rejecting, cancelling and unfriending all simply delete the row.
create table if not exists public.friendships (
    id            uuid primary key default gen_random_uuid(),
    user_a        uuid not null references public.profiles (id) on delete cascade,
    user_b        uuid not null references public.profiles (id) on delete cascade,
    requested_by  uuid not null references public.profiles (id) on delete cascade,
    status        text not null default 'pending'
                  check (status in ('pending', 'accepted')),
    created_at    timestamptz not null default now(),
    responded_at  timestamptz,
    check (user_a < user_b),
    check (requested_by in (user_a, user_b)),
    unique (user_a, user_b)
);

create index if not exists friendships_user_a_idx on public.friendships (user_a);
create index if not exists friendships_user_b_idx on public.friendships (user_b);

-- "blocker_id has blocked blocked_id".
create table if not exists public.blocks (
    blocker_id  uuid not null references public.profiles (id) on delete cascade,
    blocked_id  uuid not null references public.profiles (id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (blocker_id, blocked_id),
    check (blocker_id <> blocked_id)
);

create index if not exists blocks_blocked_idx on public.blocks (blocked_id);


-- ---------------------------------------------------------------------
-- 2. FUNCTIONS
-- Clients cannot write to these two tables directly. Every change goes
-- through one of the functions below, which checks who is calling.
-- ---------------------------------------------------------------------

-- True if either of the two users has blocked the other.
-- Only answers when the caller is one of the two users.
create or replace function public.is_blocked_between(p_a uuid, p_b uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select auth.uid() in (p_a, p_b)
       and exists (
            select 1 from public.blocks
            where (blocker_id = p_a and blocked_id = p_b)
               or (blocker_id = p_b and blocked_id = p_a)
       );
$$;

-- Sends a friend request. If the other person already sent me one,
-- this accepts it instead (both people clearly want to be friends).
create or replace function public.send_friend_request(p_other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me  uuid := auth.uid();
    v_a   uuid;
    v_b   uuid;
    v_row public.friendships%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_other is null or p_other = v_me then raise exception 'invalid_user'; end if;
    if not exists (select 1 from public.profiles where id = p_other) then
        raise exception 'user_not_found';
    end if;
    if exists (
        select 1 from public.blocks
        where (blocker_id = v_me and blocked_id = p_other)
           or (blocker_id = p_other and blocked_id = v_me)
    ) then
        raise exception 'blocked';
    end if;

    v_a := least(v_me, p_other);
    v_b := greatest(v_me, p_other);

    select * into v_row from public.friendships where user_a = v_a and user_b = v_b;

    if not found then
        insert into public.friendships (user_a, user_b, requested_by)
        values (v_a, v_b, v_me)
        on conflict (user_a, user_b) do nothing;
    elsif v_row.status = 'pending' and v_row.requested_by = p_other then
        update public.friendships
        set status = 'accepted', responded_at = now()
        where id = v_row.id;
    end if;
end;
$$;

-- Answers a request that p_other sent to me.
create or replace function public.respond_friend_request(p_other uuid, p_accept boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_a  uuid;
    v_b  uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_other is null or p_other = v_me then raise exception 'invalid_user'; end if;

    v_a := least(v_me, p_other);
    v_b := greatest(v_me, p_other);

    if p_accept then
        update public.friendships
        set status = 'accepted', responded_at = now()
        where user_a = v_a and user_b = v_b
          and status = 'pending' and requested_by = p_other;
    else
        delete from public.friendships
        where user_a = v_a and user_b = v_b
          and status = 'pending' and requested_by = p_other;
    end if;
end;
$$;

-- Unfriends p_other, or cancels the request I sent to them.
create or replace function public.remove_friend(p_other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_other is null or p_other = v_me then raise exception 'invalid_user'; end if;

    delete from public.friendships
    where user_a = least(v_me, p_other) and user_b = greatest(v_me, p_other);
end;
$$;

-- Blocks p_other. Also ends the friendship or pending request.
create or replace function public.block_user(p_other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_other is null or p_other = v_me then raise exception 'invalid_user'; end if;
    if not exists (select 1 from public.profiles where id = p_other) then
        raise exception 'user_not_found';
    end if;

    insert into public.blocks (blocker_id, blocked_id)
    values (v_me, p_other)
    on conflict do nothing;

    delete from public.friendships
    where user_a = least(v_me, p_other) and user_b = greatest(v_me, p_other);
end;
$$;

create or replace function public.unblock_user(p_other uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    delete from public.blocks where blocker_id = v_me and blocked_id = p_other;
end;
$$;

-- Replaces the version from supabase_schema.sql: a conversation can no
-- longer be opened when one of the two people has blocked the other.
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

    select id into v_id
    from public.conversations
    where user_a = v_a and user_b = v_b;

    if v_id is not null then
        return v_id;   -- old conversations stay readable even after a block
    end if;

    if exists (
        select 1 from public.blocks
        where (blocker_id = v_me and blocked_id = p_other_user)
           or (blocker_id = p_other_user and blocked_id = v_me)
    ) then
        raise exception 'blocked';
    end if;

    insert into public.conversations (user_a, user_b)
    values (v_a, v_b)
    on conflict (user_a, user_b) do nothing;

    select id into v_id
    from public.conversations
    where user_a = v_a and user_b = v_b;

    return v_id;
end;
$$;


-- ---------------------------------------------------------------------
-- 3. SECURITY
-- ---------------------------------------------------------------------

alter table public.friendships enable row level security;
alter table public.blocks      enable row level security;

revoke all on public.friendships, public.blocks from anon, authenticated;
grant select on public.friendships to authenticated;
grant select on public.blocks to authenticated;

-- I can see friendships and requests that involve me, nobody else's.
drop policy if exists "friendships: members can read" on public.friendships;
create policy "friendships: members can read"
    on public.friendships for select
    to authenticated
    using ((select auth.uid()) in (user_a, user_b));

-- I can see who I blocked. I cannot see who blocked me.
drop policy if exists "blocks: blocker can read" on public.blocks;
create policy "blocks: blocker can read"
    on public.blocks for select
    to authenticated
    using (blocker_id = (select auth.uid()));

-- Replaces the message-sending rule: same as before, plus "nobody in this
-- conversation has blocked the other".
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
              and not public.is_blocked_between(c.user_a, c.user_b)
        )
    );

revoke execute on function public.is_blocked_between(uuid, uuid)          from public, anon;
revoke execute on function public.send_friend_request(uuid)               from public, anon;
revoke execute on function public.respond_friend_request(uuid, boolean)   from public, anon;
revoke execute on function public.remove_friend(uuid)                     from public, anon;
revoke execute on function public.block_user(uuid)                        from public, anon;
revoke execute on function public.unblock_user(uuid)                      from public, anon;
revoke execute on function public.get_or_create_conversation(uuid)        from public, anon;

grant execute on function public.is_blocked_between(uuid, uuid)          to authenticated;
grant execute on function public.send_friend_request(uuid)               to authenticated;
grant execute on function public.respond_friend_request(uuid, boolean)   to authenticated;
grant execute on function public.remove_friend(uuid)                     to authenticated;
grant execute on function public.block_user(uuid)                        to authenticated;
grant execute on function public.unblock_user(uuid)                      to authenticated;
grant execute on function public.get_or_create_conversation(uuid)        to authenticated;


-- ---------------------------------------------------------------------
-- 4. REALTIME
-- Lets a friend request show up on the other phone without refreshing.
-- ---------------------------------------------------------------------

do $$
begin
    alter publication supabase_realtime add table public.friendships;
exception
    when duplicate_object then null;
end;
$$;
