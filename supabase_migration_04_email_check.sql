-- =====================================================================
-- MayChat - migration 04: tell "email not registered" from "wrong password"
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- Supabase itself answers "Invalid login credentials" in both cases.
-- This function lets the login screen ask whether an email has an account.
-- It returns only true or false, never any other data about the user.
-- =====================================================================

create or replace function public.email_registered(p_email text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1 from auth.users
        where lower(email) = lower(trim(p_email))
    );
$$;

revoke execute on function public.email_registered(text) from public;
grant execute on function public.email_registered(text) to anon, authenticated;
