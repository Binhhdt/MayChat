-- =====================================================================
-- MayChat - migration 26: the group leader can disband (delete) a group
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Run migration 21 BEFORE this file.
--
-- ADDS one function. Nothing existing is changed.
-- =====================================================================

-- Only the leader. The group disappears for every member, together with
-- its messages, reactions, pin and everybody's settings for it (the
-- database removes those by itself because they belong to the group).
create or replace function public.disband_group(p_group uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not exists (select 1 from public.groups where id = p_group and owner_id = v_me) then
        raise exception 'only_leader';
    end if;

    delete from public.groups where id = p_group;
end;
$$;

revoke execute on function public.disband_group(uuid) from public, anon;
grant execute on function public.disband_group(uuid) to authenticated;
