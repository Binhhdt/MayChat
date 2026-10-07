-- =====================================================================
-- MayChat - migration 19: place to store a relay (TURN) server for calls
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one table. Nothing existing is changed. The table starts EMPTY:
-- while it is empty, calls work exactly as before (direct connection only).
-- A relay server is only needed when two phones cannot reach each other
-- directly, for example one on Wi-Fi and one on mobile data.
-- =====================================================================

create table if not exists public.call_servers (
    id          integer generated always as identity primary key,
    urls        text not null,      -- one address, or several separated by commas
    username    text not null default '',
    credential  text not null default ''
);

alter table public.call_servers enable row level security;
revoke all on public.call_servers from anon, authenticated;
grant select on public.call_servers to authenticated;

-- Every logged-in user may read the list (the app needs it to make calls).
drop policy if exists "call_servers: logged-in users can read" on public.call_servers;
create policy "call_servers: logged-in users can read"
    on public.call_servers for select
    to authenticated
    using (true);
