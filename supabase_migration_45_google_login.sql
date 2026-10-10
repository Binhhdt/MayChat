-- =====================================================================
-- MayChat 0.41.0 — "Tiếp tục với Google"
--  * A new account made with Google has no username yet (Google does not
--    give one): the profile is made a moment later, when the person picks
--    a username on the "Hoàn tất hồ sơ" screen (profile_complete below).
--  * Accounts made with email and password work exactly as before.
-- Before this file: Supabase → Authentication → Providers → Google switched on.
-- Run once in Supabase → SQL Editor. Safe to run again.
-- =====================================================================

-- 1. A new account: with email (username given) as before; with Google, no
--    profile yet.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_username text := lower(trim(new.raw_user_meta_data ->> 'username'));
    v_name     text := trim(new.raw_user_meta_data ->> 'display_name');
begin
    if v_username is null
       and coalesce(new.raw_app_meta_data ->> 'provider', 'email') <> 'email' then
        return new;   -- Google: the app asks for the username afterwards
    end if;

    if v_username is null or v_username !~ '^[a-z0-9_]{3,20}$' then
        raise exception 'invalid_username';
    end if;

    insert into public.profiles (id, username, display_name)
    values (new.id, v_username, coalesce(nullif(v_name, ''), v_username));

    return new;
end;
$$;

-- 2. "Hoàn tất hồ sơ": the profile of an account made with Google.
create or replace function public.profile_complete(p_username text, p_display_name text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_username text := lower(trim(coalesce(p_username, '')));
    v_name text := regexp_replace(trim(coalesce(p_display_name, '')), '\s+', ' ', 'g');
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if exists (select 1 from public.profiles where id = v_me) then
        return;   -- already done (pressed twice, or an old account)
    end if;
    if v_username !~ '^[a-z0-9_]{3,20}$' then raise exception 'invalid_username'; end if;
    if char_length(v_name) < 1 or char_length(v_name) > 50 then raise exception 'invalid_display_name'; end if;
    if exists (select 1 from public.profiles where username = v_username) then
        raise exception 'username_taken';
    end if;
    insert into public.profiles (id, username, display_name) values (v_me, v_username, v_name);
exception
    when unique_violation then raise exception 'username_taken';
end;
$$;

revoke all on function public.profile_complete(text, text) from public, anon;
grant execute on function public.profile_complete(text, text) to authenticated;
