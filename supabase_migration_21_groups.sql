-- =====================================================================
-- MayChat - migration 21: group chats (phase 1)
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS three tables, their rules, eleven functions, two triggers and two
-- storage rules. The tables of the one-to-one chats are not touched.
-- CHANGES one existing function: push_payload (migration 06). Its part for
-- one-to-one messages is kept word for word; a second part is added that
-- prepares the notification of a GROUP message.
--
-- Rules of a group, as agreed:
--   * every member may add new members (only their own friends);
--   * only the group leader may remove a member;
--   * at most 50 members.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. TABLES
-- ---------------------------------------------------------------------

create table if not exists public.groups (
    id                 uuid primary key default gen_random_uuid(),
    name               text not null check (char_length(trim(name)) between 1 and 60),
    owner_id           uuid not null references public.profiles (id),   -- the group leader
    created_at         timestamptz not null default now(),
    last_message_text  text,
    last_message_at    timestamptz,
    last_sender_id     uuid
);

create table if not exists public.group_members (
    group_id      uuid not null references public.groups (id) on delete cascade,
    user_id       uuid not null references public.profiles (id) on delete cascade,
    joined_at     timestamptz not null default now(),
    last_read_at  timestamptz not null default now(),   -- for the unread number
    primary key (group_id, user_id)
);

create index if not exists group_members_user_idx on public.group_members (user_id);

create table if not exists public.group_messages (
    id           uuid primary key default gen_random_uuid(),
    group_id     uuid not null references public.groups (id) on delete cascade,
    sender_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
    content      text not null check (char_length(content) between 1 and 4000),
    kind         text not null default 'text'
                 check (kind in ('text', 'image', 'voice', 'file', 'system')),
    media_path   text,
    duration_ms  integer check (duration_ms is null or duration_ms between 0 and 600000),
    file_name    text check (file_name is null or char_length(file_name) between 1 and 200),
    file_size    integer check (file_size is null or file_size between 0 and 5242880),
    created_at   timestamptz not null default now(),
    -- A text or a notice has no file; everything else has one, stored in
    -- the folder named after the group.
    check (
        (kind in ('text', 'system') and media_path is null)
        or (kind not in ('text', 'system')
            and media_path is not null
            and media_path like group_id::text || '/%')
    )
);

create index if not exists group_messages_group_created_idx
    on public.group_messages (group_id, created_at desc);
create index if not exists group_messages_sender_created_idx
    on public.group_messages (sender_id, created_at desc);


-- ---------------------------------------------------------------------
-- 2. WHO MAY SEE AND WRITE WHAT
-- ---------------------------------------------------------------------

-- True when the logged-in user is a member of the group.
create or replace function public.is_group_member(p_group uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1 from public.group_members
        where group_id = p_group and user_id = auth.uid()
    );
$$;

alter table public.groups         enable row level security;
alter table public.group_members  enable row level security;
alter table public.group_messages enable row level security;

revoke all on public.groups         from anon, authenticated;
revoke all on public.group_members  from anon, authenticated;
revoke all on public.group_messages from anon, authenticated;

grant select on public.groups         to authenticated;
grant select on public.group_members  to authenticated;
grant select on public.group_messages to authenticated;
-- The app may write only these columns of a message, never "system" ones.
grant insert (group_id, content, kind, media_path, duration_ms, file_name, file_size)
    on public.group_messages to authenticated;

drop policy if exists "groups: members can read" on public.groups;
create policy "groups: members can read"
    on public.groups for select to authenticated
    using (public.is_group_member(id));

drop policy if exists "group_members: members can read" on public.group_members;
create policy "group_members: members can read"
    on public.group_members for select to authenticated
    using (public.is_group_member(group_id));

drop policy if exists "group_messages: members can read" on public.group_messages;
create policy "group_messages: members can read"
    on public.group_messages for select to authenticated
    using (public.is_group_member(group_id));

drop policy if exists "group_messages: members can send" on public.group_messages;
create policy "group_messages: members can send"
    on public.group_messages for insert to authenticated
    with check (
        sender_id = (select auth.uid())
        and kind <> 'system'
        and public.is_group_member(group_id)
    );

-- Files of a group live in the existing "chat-media" bucket, in a folder
-- named after the group. Two extra rules let its members use that folder.
drop policy if exists "chat-media: group members can read" on storage.objects;
create policy "chat-media: group members can read"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'chat-media'
        and exists (
            select 1 from public.group_members gm
            where gm.group_id::text = (storage.foldername(name))[1]
              and gm.user_id = (select auth.uid())
        )
    );

drop policy if exists "chat-media: group members can upload" on storage.objects;
create policy "chat-media: group members can upload"
    on storage.objects for insert to authenticated
    with check (
        bucket_id = 'chat-media'
        and exists (
            select 1 from public.group_members gm
            where gm.group_id::text = (storage.foldername(name))[1]
              and gm.user_id = (select auth.uid())
        )
    );


-- ---------------------------------------------------------------------
-- 3. AFTER / BEFORE A GROUP MESSAGE IS WRITTEN
-- ---------------------------------------------------------------------

-- Keeps the preview of the group (shown in the conversation list) current.
create or replace function public.handle_new_group_message()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.groups
    set last_message_text = left(new.content, 120),
        last_message_at = new.created_at,
        last_sender_id = new.sender_id
    where id = new.group_id;
    return new;
end;
$$;

drop trigger if exists on_group_message_created on public.group_messages;
create trigger on_group_message_created
    after insert on public.group_messages
    for each row execute function public.handle_new_group_message();

-- Speed limit, same numbers as for one-to-one messages.
create or replace function public.limit_group_message_rate()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_recent integer;
begin
    select count(*) into v_recent
    from public.group_messages
    where sender_id = new.sender_id
      and created_at > now() - interval '10 seconds';
    if v_recent >= 60 then raise exception 'rate_limited'; end if;
    return new;
end;
$$;

drop trigger if exists group_messages_rate_limit on public.group_messages;
create trigger group_messages_rate_limit
    before insert on public.group_messages
    for each row execute function public.limit_group_message_rate();


-- ---------------------------------------------------------------------
-- 4. CREATING AND MANAGING A GROUP
-- ---------------------------------------------------------------------

-- True when the logged-in user and p_other are accepted friends.
create or replace function public.is_my_friend(p_other uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1 from public.friendships f
        where f.status = 'accepted'
          and ((f.user_a = auth.uid() and f.user_b = p_other)
            or (f.user_b = auth.uid() and f.user_a = p_other))
    );
$$;

-- Creates a group with me as leader. p_members: my friends to add.
create or replace function public.create_group(p_name text, p_members uuid[])
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me      uuid := auth.uid();
    v_name    text := trim(coalesce(p_name, ''));
    v_group   uuid;
    v_member  uuid;
    v_count   integer := 1;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if char_length(v_name) < 1 or char_length(v_name) > 60 then
        raise exception 'invalid_group_name';
    end if;

    insert into public.groups (name, owner_id) values (v_name, v_me) returning id into v_group;
    insert into public.group_members (group_id, user_id) values (v_group, v_me);

    foreach v_member in array coalesce(p_members, '{}'::uuid[]) loop
        if v_member <> v_me and public.is_my_friend(v_member) then
            if v_count >= 50 then raise exception 'group_full'; end if;
            insert into public.group_members (group_id, user_id)
            values (v_group, v_member)
            on conflict do nothing;
            if found then v_count := v_count + 1; end if;
        end if;
    end loop;

    insert into public.group_messages (group_id, sender_id, content, kind)
    values (v_group, v_me, 'đã tạo nhóm', 'system');
    return v_group;
end;
$$;

-- Any member may add people, but only their own friends, up to 50 members.
create or replace function public.add_group_members(p_group uuid, p_members uuid[])
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me      uuid := auth.uid();
    v_member  uuid;
    v_count   integer;
    v_name    text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;

    select count(*) into v_count from public.group_members where group_id = p_group;

    foreach v_member in array coalesce(p_members, '{}'::uuid[]) loop
        if public.is_my_friend(v_member) then
            if v_count >= 50 then raise exception 'group_full'; end if;
            insert into public.group_members (group_id, user_id)
            values (p_group, v_member)
            on conflict do nothing;
            if found then
                v_count := v_count + 1;
                select display_name into v_name from public.profiles where id = v_member;
                insert into public.group_messages (group_id, sender_id, content, kind)
                values (p_group, v_me, 'đã thêm ' || coalesce(v_name, 'một thành viên') || ' vào nhóm', 'system');
            end if;
        end if;
    end loop;
end;
$$;

-- Only the group leader may remove a member (and not themselves: they leave).
create or replace function public.remove_group_member(p_group uuid, p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_name text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not exists (select 1 from public.groups where id = p_group and owner_id = v_me) then
        raise exception 'only_leader';
    end if;
    if p_user = v_me then raise exception 'not_allowed'; end if;

    delete from public.group_members where group_id = p_group and user_id = p_user;
    if found then
        select display_name into v_name from public.profiles where id = p_user;
        insert into public.group_messages (group_id, sender_id, content, kind)
        values (p_group, v_me, 'đã xóa ' || coalesce(v_name, 'một thành viên') || ' khỏi nhóm', 'system');
    end if;
end;
$$;

-- I leave the group. If I was the leader, the member who joined earliest
-- becomes leader. If nobody is left, the group is deleted.
create or replace function public.leave_group(p_group uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_next  uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then return; end if;

    insert into public.group_messages (group_id, sender_id, content, kind)
    values (p_group, v_me, 'đã rời nhóm', 'system');

    delete from public.group_members where group_id = p_group and user_id = v_me;

    select user_id into v_next
    from public.group_members
    where group_id = p_group
    order by joined_at
    limit 1;

    if v_next is null then
        delete from public.groups where id = p_group;
    else
        update public.groups set owner_id = v_next where id = p_group and owner_id = v_me;
    end if;
end;
$$;

-- Any member may rename the group.
create or replace function public.rename_group(p_group uuid, p_name text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_name text := trim(coalesce(p_name, ''));
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;
    if char_length(v_name) < 1 or char_length(v_name) > 60 then
        raise exception 'invalid_group_name';
    end if;

    update public.groups set name = v_name where id = p_group and name <> v_name;
    if found then
        insert into public.group_messages (group_id, sender_id, content, kind)
        values (p_group, v_me, 'đã đổi tên nhóm thành "' || v_name || '"', 'system');
    end if;
end;
$$;

-- "I have read this group up to now."
create or replace function public.mark_group_read(p_group uuid)
returns void
language sql
security definer
set search_path = ''
as $$
    update public.group_members
    set last_read_at = now()
    where group_id = p_group and user_id = auth.uid();
$$;

-- How many unread messages I have in each of my groups.
create or replace function public.group_unread_counts()
returns table (group_id uuid, unread integer)
language sql
stable
security definer
set search_path = ''
as $$
    select gm.group_id, count(*)::integer
    from public.group_members gm
    join public.group_messages m on m.group_id = gm.group_id
    where gm.user_id = auth.uid()
      and m.sender_id <> auth.uid()
      and m.kind <> 'system'
      and m.created_at > gm.last_read_at
    group by gm.group_id;
$$;


-- ---------------------------------------------------------------------
-- 5. NOTIFICATIONS FOR GROUP MESSAGES
-- ---------------------------------------------------------------------

-- Asks the notification function to announce a new group message.
create or replace function public.handle_push_on_group_message()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_cfg public.push_config%rowtype;
begin
    if new.kind = 'system' then return new; end if;

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

drop trigger if exists on_group_message_push on public.group_messages;
create trigger on_group_message_push
    after insert on public.group_messages
    for each row execute function public.handle_push_on_group_message();

-- What the notification function needs to know about one message.
-- PART 1 (one-to-one message) is exactly the function of migration 06.
-- PART 2 (group message) is new.
create or replace function public.push_payload(p_message_id uuid, p_secret text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_msg       public.messages%rowtype;
    v_conv      public.conversations%rowtype;
    v_recipient uuid;
    v_tokens    jsonb;
    v_name      text;
    v_unread    integer;
    v_gmsg      public.group_messages%rowtype;
    v_group     public.groups%rowtype;
begin
    if not exists (select 1 from public.push_config where secret = p_secret) then
        return null;
    end if;

    select * into v_msg from public.messages where id = p_message_id;
    if found then
        -- ---------------- PART 1: one-to-one message (unchanged) ----------------
        if v_msg.read_at is not null then
            return null;   -- already read: nothing to notify
        end if;

        select * into v_conv from public.conversations where id = v_msg.conversation_id;
        if not found then return null; end if;

        v_recipient := case when v_conv.user_a = v_msg.sender_id then v_conv.user_b else v_conv.user_a end;

        select coalesce(jsonb_agg(token), '[]'::jsonb) into v_tokens
        from public.push_tokens where user_id = v_recipient;

        select display_name into v_name from public.profiles where id = v_msg.sender_id;

        -- Total unread messages of the recipient: the number for the app icon.
        select count(*) into v_unread
        from public.messages m
        join public.conversations c on c.id = m.conversation_id
        where v_recipient in (c.user_a, c.user_b)
          and m.sender_id <> v_recipient
          and m.read_at is null;

        return jsonb_build_object(
            'tokens', v_tokens,
            'title', coalesce(v_name, 'MayChat'),
            'body', left(v_msg.content, 120),
            'conversation_id', v_msg.conversation_id,
            'sender_id', v_msg.sender_id,
            'unread', v_unread
        );
    end if;

    -- ---------------- PART 2: group message (new) ----------------
    select * into v_gmsg from public.group_messages where id = p_message_id;
    if not found then return null; end if;

    select * into v_group from public.groups where id = v_gmsg.group_id;
    if not found then return null; end if;

    -- Phones of every member except the sender, leaving out people who
    -- switched message notifications off.
    select coalesce(jsonb_agg(t.token), '[]'::jsonb) into v_tokens
    from public.push_tokens t
    join public.group_members gm on gm.user_id = t.user_id
    where gm.group_id = v_gmsg.group_id
      and t.user_id <> v_gmsg.sender_id
      and not exists (
          select 1 from public.user_settings s
          where s.user_id = t.user_id and s.mute_messages
      );

    select display_name into v_name from public.profiles where id = v_gmsg.sender_id;

    return jsonb_build_object(
        'tokens', v_tokens,
        'title', v_group.name,
        'body', coalesce(v_name, 'Ai đó') || ': ' || left(v_gmsg.content, 100),
        'conversation_id', v_gmsg.group_id,
        'sender_id', v_gmsg.sender_id,
        'unread', 1
    );
end;
$$;


-- ---------------------------------------------------------------------
-- 6. PERMISSIONS
-- ---------------------------------------------------------------------

revoke execute on function public.is_group_member(uuid)               from public, anon;
revoke execute on function public.is_my_friend(uuid)                  from public, anon;
revoke execute on function public.create_group(text, uuid[])          from public, anon;
revoke execute on function public.add_group_members(uuid, uuid[])     from public, anon;
revoke execute on function public.remove_group_member(uuid, uuid)     from public, anon;
revoke execute on function public.leave_group(uuid)                   from public, anon;
revoke execute on function public.rename_group(uuid, text)            from public, anon;
revoke execute on function public.mark_group_read(uuid)               from public, anon;
revoke execute on function public.group_unread_counts()               from public, anon;
revoke execute on function public.handle_new_group_message()          from public, anon, authenticated;
revoke execute on function public.limit_group_message_rate()          from public, anon, authenticated;
revoke execute on function public.handle_push_on_group_message()      from public, anon, authenticated;
revoke execute on function public.push_payload(uuid, text)            from public, anon, authenticated;

grant execute on function public.is_group_member(uuid)                to authenticated;
grant execute on function public.is_my_friend(uuid)                   to authenticated;
grant execute on function public.create_group(text, uuid[])           to authenticated;
grant execute on function public.add_group_members(uuid, uuid[])      to authenticated;
grant execute on function public.remove_group_member(uuid, uuid)      to authenticated;
grant execute on function public.leave_group(uuid)                    to authenticated;
grant execute on function public.rename_group(uuid, text)             to authenticated;
grant execute on function public.mark_group_read(uuid)                to authenticated;
grant execute on function public.group_unread_counts()                to authenticated;
grant execute on function public.push_payload(uuid, text)             to service_role;
