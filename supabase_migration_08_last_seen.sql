-- =====================================================================
-- MayChat - migration 08: "offline for how long"
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one function only. It writes the server's current time into the
-- column profiles.last_seen_at, which has existed since the first schema.
-- No existing table, rule or function is changed.
-- =====================================================================

create or replace function public.touch_last_seen()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.profiles set last_seen_at = now() where id = auth.uid();
end;
$$;

revoke execute on function public.touch_last_seen() from public, anon;
grant execute on function public.touch_last_seen() to authenticated;
