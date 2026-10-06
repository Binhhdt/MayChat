-- =====================================================================
-- MayChat - migration 05: one device at a time per account
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- Each account has at most one row here: the device that is using it now.
-- The app "claims" the account when it logs in and then again every 30
-- seconds while it is on screen. A claim from a DIFFERENT device is refused
-- as long as the current device has checked in during the last 90 seconds.
-- =====================================================================

create table if not exists public.active_sessions (
    user_id    uuid primary key references public.profiles (id) on delete cascade,
    device_id  text not null check (char_length(device_id) between 8 and 100),
    last_seen  timestamptz not null default now()
);

-- Nobody reads or writes this table directly; only the functions below do.
alter table public.active_sessions enable row level security;
revoke all on public.active_sessions from anon, authenticated;

-- Returns true if this device now holds the account, false if another
-- device is using it right now.
create or replace function public.claim_session(p_device text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    -- One atomic statement: take the account if it is free, already mine,
    -- or the other device has been silent for more than 90 seconds.
    insert into public.active_sessions as s (user_id, device_id, last_seen)
    values (v_me, p_device, now())
    on conflict (user_id) do update
        set device_id = excluded.device_id,
            last_seen = now()
        where s.device_id = excluded.device_id
           or s.last_seen < now() - interval '90 seconds';

    return exists (
        select 1 from public.active_sessions
        where user_id = v_me and device_id = p_device
    );
end;
$$;

-- Called on sign-out, so the account is free for another device at once.
create or replace function public.release_session(p_device text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from public.active_sessions
    where user_id = auth.uid() and device_id = p_device;
end;
$$;

revoke execute on function public.claim_session(text)   from public, anon;
revoke execute on function public.release_session(text) from public, anon;
grant execute on function public.claim_session(text)    to authenticated;
grant execute on function public.release_session(text)  to authenticated;
