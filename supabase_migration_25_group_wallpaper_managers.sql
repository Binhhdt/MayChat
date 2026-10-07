-- =====================================================================
-- MayChat - migration 25: only the group leader and the deputies may
-- change the background of a group
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Run migrations 22 and 24 BEFORE this file.
--
-- CHANGES one existing function: set_group_wallpaper (migration 22).
-- Before: every member could change the background. Now: only the leader
-- and the deputies. Everything else in the function is unchanged.
-- Nothing is added and no table is touched.
-- =====================================================================

create or replace function public.set_group_wallpaper(p_group uuid, p_value text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_value text := nullif(trim(coalesce(p_value, '')), '');
    v_old   text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;
    -- NEW: leader or deputy only.
    if not public.is_group_manager(p_group) then raise exception 'only_leader'; end if;

    if v_value is not null
       and v_value not in ('mint', 'peach', 'sky', 'lilac', 'sand')
       and v_value not like 'img:' || p_group::text || '/%' then
        raise exception 'invalid_wallpaper';
    end if;

    select wallpaper into v_old from public.groups where id = p_group;
    if not found then raise exception 'not_allowed'; end if;

    -- Nothing to do when the same background is chosen again.
    if v_old is not distinct from v_value then return; end if;

    update public.groups set wallpaper = v_value where id = p_group;

    insert into public.group_messages (group_id, sender_id, content, kind)
    values (
        p_group,
        v_me,
        case when v_value is null then 'đã xóa hình nền' else 'đã thay đổi hình nền' end,
        'system'
    );
end;
$$;

revoke execute on function public.set_group_wallpaper(uuid, text) from public, anon;
grant execute on function public.set_group_wallpaper(uuid, text) to authenticated;
