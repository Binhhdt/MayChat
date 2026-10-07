-- =====================================================================
-- MayChat - migration 20: a second, dependable path for "set up a call"
-- messages
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one table and two functions. Nothing existing is changed.
--
-- Until now the small messages that set up a call (I am calling / I picked
-- up / hang up) travelled only over the live connection. When that
-- connection is not healthy on one phone, the call never gets through.
-- From app version 0.20.0 every such message is ALSO written here, and the
-- other phone fetches it about once a second while a call is being set up.
-- Rows live for seconds only; nothing about a call is kept.
-- =====================================================================

create table if not exists public.call_signals (
    id          bigint generated always as identity primary key,
    to_user     uuid not null references public.profiles (id) on delete cascade,
    from_user   uuid not null references public.profiles (id) on delete cascade,
    payload     jsonb not null,
    created_at  timestamptz not null default now()
);

create index if not exists call_signals_to_user_idx on public.call_signals (to_user, id);

-- Nobody reads or writes the table directly; only the two functions below.
alter table public.call_signals enable row level security;
revoke all on public.call_signals from anon, authenticated;

-- Leave a call message for someone I have a conversation with.
create or replace function public.send_call_signal(p_to uuid, p_payload jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_payload is null or pg_column_size(p_payload) > 60000 then
        raise exception 'invalid_payload';
    end if;
    if not exists (
        select 1 from public.conversations c
        where (c.user_a = v_me and c.user_b = p_to)
           or (c.user_b = v_me and c.user_a = p_to)
    ) then
        raise exception 'not_allowed';
    end if;

    -- Housekeeping: anything older than two minutes is useless.
    delete from public.call_signals where created_at < now() - interval '2 minutes';

    -- "from" is always set by the server to the real sender, so nobody can
    -- pretend to be someone else on this path.
    insert into public.call_signals (to_user, from_user, payload)
    values (p_to, v_me, p_payload || jsonb_build_object('from', v_me::text));
end;
$$;

-- Fetch (and remove) the call messages waiting for me, oldest first.
-- Messages older than 20 seconds are removed without being returned.
create or replace function public.take_call_signals()
returns setof jsonb
language sql
security definer
set search_path = ''
as $$
    with taken as (
        delete from public.call_signals
        where to_user = auth.uid()
        returning id, payload, created_at
    )
    select payload
    from taken
    where created_at > now() - interval '20 seconds'
    order by id;
$$;

revoke execute on function public.send_call_signal(uuid, jsonb) from public, anon;
revoke execute on function public.take_call_signals()           from public, anon;
grant execute on function public.send_call_signal(uuid, jsonb)  to authenticated;
grant execute on function public.take_call_signals()            to authenticated;
