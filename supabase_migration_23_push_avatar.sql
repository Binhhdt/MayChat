-- =====================================================================
-- MayChat - migration 23: the sender's picture on message notifications
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one function. Nothing existing is changed.
-- It is used only by the notification function (clever-worker), which asks
-- where the avatar of the person who sent a message is stored.
-- =====================================================================

-- Returns the storage path of a user's avatar, or null when they have none
-- (or when the password is wrong).
create or replace function public.push_avatar(p_user uuid, p_secret text)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_path text;
begin
    if not exists (select 1 from public.push_config where secret = p_secret) then
        return null;
    end if;
    select avatar_path into v_path from public.profiles where id = p_user;
    return nullif(v_path, '');
end;
$$;

revoke execute on function public.push_avatar(uuid, text) from public, anon, authenticated;
grant execute on function public.push_avatar(uuid, text) to service_role;
