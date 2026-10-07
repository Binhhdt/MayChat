-- =====================================================================
-- MayChat - migration 24: group picture, deputy leaders, handing over the
-- leadership, and live delivery of group messages
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Run migrations 21 and 22 BEFORE this file.
--
-- ADDS: one column to groups, one column to group_members, three
-- functions, and puts the table group_messages on the live connection.
--
-- CHANGES two existing functions, only to let DEPUTY LEADERS use them:
--   1. rename_group        (migration 22): leader OR deputy may rename.
--   2. remove_group_member (migration 21): the leader may remove anyone;
--      a deputy may remove ordinary members (not the leader, not another
--      deputy). Ordinary members still cannot remove anyone.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. NEW COLUMNS
-- ---------------------------------------------------------------------

-- Picture of the group: a file in the "avatars" storage (null = none).
alter table public.groups add column if not exists avatar_path text;

-- 'member' or 'deputy'. The leader is groups.owner_id, as before.
alter table public.group_members add column if not exists role text not null default 'member';
alter table public.group_members drop constraint if exists group_members_role_check;
alter table public.group_members add constraint group_members_role_check
    check (role in ('member', 'deputy'));


-- ---------------------------------------------------------------------
-- 2. WHO MANAGES THE GROUP
-- ---------------------------------------------------------------------

-- True when the logged-in user is the leader or a deputy of the group.
create or replace function public.is_group_manager(p_group uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (select 1 from public.groups where id = p_group and owner_id = auth.uid())
        or exists (
            select 1 from public.group_members
            where group_id = p_group and user_id = auth.uid() and role = 'deputy'
        );
$$;

-- The leader appoints a deputy (p_on = true) or takes the role back.
create or replace function public.set_group_deputy(p_group uuid, p_user uuid, p_on boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_role text := case when coalesce(p_on, false) then 'deputy' else 'member' end;
    v_name text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not exists (select 1 from public.groups where id = p_group and owner_id = v_me) then
        raise exception 'only_leader';
    end if;
    if p_user = v_me then raise exception 'not_allowed'; end if;

    update public.group_members
    set role = v_role
    where group_id = p_group and user_id = p_user and role <> v_role;

    if found then
        select display_name into v_name from public.profiles where id = p_user;
        insert into public.group_messages (group_id, sender_id, content, kind)
        values (
            p_group,
            v_me,
            case when v_role = 'deputy'
                then 'đã bổ nhiệm ' || coalesce(v_name, 'một thành viên') || ' làm phó nhóm'
                else 'đã bãi nhiệm phó nhóm ' || coalesce(v_name, 'một thành viên')
            end,
            'system'
        );
    end if;
end;
$$;

-- The leader hands the leadership over to another member and becomes an
-- ordinary member.
create or replace function public.transfer_group_leader(p_group uuid, p_user uuid)
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
    if p_user = v_me then return; end if;
    if not exists (
        select 1 from public.group_members where group_id = p_group and user_id = p_user
    ) then
        raise exception 'not_allowed';
    end if;

    update public.groups set owner_id = p_user where id = p_group;
    -- The new leader no longer needs the deputy role.
    update public.group_members set role = 'member' where group_id = p_group and user_id = p_user;

    select display_name into v_name from public.profiles where id = p_user;
    insert into public.group_messages (group_id, sender_id, content, kind)
    values (p_group, v_me, 'đã chuyển quyền trưởng nhóm cho ' || coalesce(v_name, 'một thành viên'), 'system');
end;
$$;

-- Leader or deputy sets the picture of the group. The file is one the
-- caller uploaded into THEIR OWN folder of the "avatars" storage.
-- p_path empty = remove the picture.
create or replace function public.set_group_avatar(p_group uuid, p_path text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_path text := nullif(trim(coalesce(p_path, '')), '');
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_manager(p_group) then raise exception 'only_leader'; end if;
    if v_path is not null and v_path not like v_me::text || '/%' then
        raise exception 'not_allowed';
    end if;

    update public.groups set avatar_path = v_path
    where id = p_group and avatar_path is distinct from v_path;
    if found then
        insert into public.group_messages (group_id, sender_id, content, kind)
        values (
            p_group,
            v_me,
            case when v_path is null then 'đã xóa ảnh nhóm' else 'đã đổi ảnh nhóm' end,
            'system'
        );
    end if;
end;
$$;


-- ---------------------------------------------------------------------
-- 3. EXISTING FUNCTIONS, NOW ALSO FOR DEPUTIES
-- ---------------------------------------------------------------------

-- Leader or deputy may rename the group (before: leader only).
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
    if not public.is_group_manager(p_group) then raise exception 'only_leader'; end if;
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

-- The leader may remove anyone; a deputy may remove ordinary members.
create or replace function public.remove_group_member(p_group uuid, p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me        uuid := auth.uid();
    v_name      text;
    v_owner     uuid;
    v_target    text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select owner_id into v_owner from public.groups where id = p_group;
    if not found then raise exception 'not_allowed'; end if;
    if p_user = v_me then raise exception 'not_allowed'; end if;

    if v_owner <> v_me then
        -- Not the leader: must be a deputy, and the target an ordinary member.
        if not public.is_group_manager(p_group) then raise exception 'only_leader'; end if;
        select role into v_target from public.group_members
        where group_id = p_group and user_id = p_user;
        if p_user = v_owner or v_target = 'deputy' then raise exception 'only_leader'; end if;
    end if;

    delete from public.group_members where group_id = p_group and user_id = p_user;
    if found then
        select display_name into v_name from public.profiles where id = p_user;
        insert into public.group_messages (group_id, sender_id, content, kind)
        values (p_group, v_me, 'đã xóa ' || coalesce(v_name, 'một thành viên') || ' khỏi nhóm', 'system');
    end if;
end;
$$;


-- ---------------------------------------------------------------------
-- 4. LIVE DELIVERY OF GROUP MESSAGES
-- Members get a new group message at once instead of at the next check.
-- (Only rows a user may read are sent to that user.)
-- ---------------------------------------------------------------------

do $$
begin
    alter publication supabase_realtime add table public.group_messages;
exception
    when duplicate_object then null;
end;
$$;


-- ---------------------------------------------------------------------
-- 5. PERMISSIONS
-- ---------------------------------------------------------------------

revoke execute on function public.is_group_manager(uuid)                 from public, anon;
revoke execute on function public.set_group_deputy(uuid, uuid, boolean)  from public, anon;
revoke execute on function public.transfer_group_leader(uuid, uuid)      from public, anon;
revoke execute on function public.set_group_avatar(uuid, text)           from public, anon;
revoke execute on function public.rename_group(uuid, text)               from public, anon;
revoke execute on function public.remove_group_member(uuid, uuid)        from public, anon;

grant execute on function public.is_group_manager(uuid)                  to authenticated;
grant execute on function public.set_group_deputy(uuid, uuid, boolean)   to authenticated;
grant execute on function public.transfer_group_leader(uuid, uuid)       to authenticated;
grant execute on function public.set_group_avatar(uuid, text)            to authenticated;
grant execute on function public.rename_group(uuid, text)                to authenticated;
grant execute on function public.remove_group_member(uuid, uuid)         to authenticated;
