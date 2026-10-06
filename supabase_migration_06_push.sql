-- =====================================================================
-- MayChat - migration 06: push notifications
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- What happens after this:
--   new message saved -> database calls the Edge Function "push" ->
--   the function asks the database who to notify -> sends it through
--   Firebase Cloud Messaging to the recipient's phone.
--
-- Free-plan features only: Postgres, the pg_net extension, Edge Functions.
-- =====================================================================

-- Lets the database make an HTTP call (used to wake the Edge Function).
create extension if not exists pg_net with schema extensions;


-- ---------------------------------------------------------------------
-- 1. TABLES
-- ---------------------------------------------------------------------

-- One row per app installation: which account is logged in there, and the
-- Firebase address ("token") of that phone.
create table if not exists public.push_tokens (
    device_id   text primary key check (char_length(device_id) between 8 and 100),
    user_id     uuid not null references public.profiles (id) on delete cascade,
    token       text not null unique check (char_length(token) between 20 and 4096),
    updated_at  timestamptz not null default now()
);

create index if not exists push_tokens_user_idx on public.push_tokens (user_id);

-- Exactly one row: where the Edge Function lives, and a random password
-- that only the database and the Edge Function know.
create table if not exists public.push_config (
    id            boolean primary key default true check (id),
    function_url  text not null,
    secret        text not null
);

insert into public.push_config (id, function_url, secret)
values (
    true,
    'https://ngwdlsoarqxktiovnptl.supabase.co/functions/v1/push',
    replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '')
)
on conflict (id) do nothing;

-- The app can never read or write these tables directly.
alter table public.push_tokens enable row level security;
alter table public.push_config enable row level security;
revoke all on public.push_tokens, public.push_config from anon, authenticated;


-- ---------------------------------------------------------------------
-- 2. FUNCTIONS CALLED BY THE APP
-- ---------------------------------------------------------------------

-- "This installation, logged in as me, can be reached at this token."
create or replace function public.register_push_token(p_device text, p_token text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    -- A token belongs to one installation only.
    delete from public.push_tokens where token = p_token and device_id <> p_device;

    insert into public.push_tokens (device_id, user_id, token, updated_at)
    values (p_device, v_me, p_token, now())
    on conflict (device_id) do update
        set user_id = excluded.user_id,
            token = excluded.token,
            updated_at = now();
end;
$$;

-- Called on sign-out: this phone must stop receiving my notifications.
create or replace function public.unregister_push_token(p_device text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from public.push_tokens
    where device_id = p_device and user_id = auth.uid();
end;
$$;


-- ---------------------------------------------------------------------
-- 3. FUNCTIONS CALLED BY THE EDGE FUNCTION
-- Both check the shared password, so nobody else can use them.
-- ---------------------------------------------------------------------

-- Everything needed to notify the recipient of one message.
create or replace function public.push_payload(p_message_id uuid, p_secret text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_msg       public.messages%rowtype;
    v_conv      public.conversations%rowtype;
    v_recipient uuid;
    v_tokens    jsonb;
    v_name      text;
    v_unread    integer;
begin
    if not exists (select 1 from public.push_config where secret = p_secret) then
        return null;
    end if;

    select * into v_msg from public.messages where id = p_message_id;
    if not found or v_msg.read_at is not null then
        return null;   -- unknown message, or already read: nothing to notify
    end if;

    select * into v_conv from public.conversations where id = v_msg.conversation_id;
    if not found then return null; end if;

    v_recipient := case when v_conv.user_a = v_msg.sender_id then v_conv.user_b else v_conv.user_a end;

    select coalesce(jsonb_agg(token), '[]'::jsonb) into v_tokens
    from public.push_tokens where user_id = v_recipient;

    select display_name into v_name from public.profiles where id = v_msg.sender_id;

    -- Total unread messages of the recipient: the number for the app icon.
    select count(*) into v_unread
    from public.messages m
    join public.conversations c on c.id = m.conversation_id
    where v_recipient in (c.user_a, c.user_b)
      and m.sender_id <> v_recipient
      and m.read_at is null;

    return jsonb_build_object(
        'tokens', v_tokens,
        'title', coalesce(v_name, 'MayChat'),
        'body', left(v_msg.content, 120),
        'conversation_id', v_msg.conversation_id,
        'sender_id', v_msg.sender_id,
        'unread', v_unread
    );
end;
$$;

-- Firebase said this token no longer exists (app uninstalled): forget it.
create or replace function public.remove_push_token(p_token text, p_secret text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if exists (select 1 from public.push_config where secret = p_secret) then
        delete from public.push_tokens where token = p_token;
    end if;
end;
$$;


-- ---------------------------------------------------------------------
-- 4. TRIGGER: after each new message, wake the Edge Function
-- ---------------------------------------------------------------------

create or replace function public.handle_push_on_message()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_cfg       public.push_config%rowtype;
    v_recipient uuid;
begin
    select case when c.user_a = new.sender_id then c.user_b else c.user_a end
    into v_recipient
    from public.conversations c
    where c.id = new.conversation_id;

    -- No phone registered for the recipient: do not call the function at all.
    if v_recipient is null
       or not exists (select 1 from public.push_tokens where user_id = v_recipient) then
        return new;
    end if;

    select * into v_cfg from public.push_config where id;
    if not found then return new; end if;

    perform net.http_post(
        url := v_cfg.function_url,
        body := jsonb_build_object('message_id', new.id),
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'x-push-secret', v_cfg.secret
        )
    );
    return new;
exception
    when others then
        -- A notification problem must never stop a message from being sent.
        return new;
end;
$$;

drop trigger if exists on_message_push on public.messages;
create trigger on_message_push
    after insert on public.messages
    for each row execute function public.handle_push_on_message();


-- ---------------------------------------------------------------------
-- 5. PERMISSIONS
-- ---------------------------------------------------------------------

revoke execute on function public.register_push_token(text, text)   from public, anon;
revoke execute on function public.unregister_push_token(text)       from public, anon;
revoke execute on function public.push_payload(uuid, text)          from public, anon, authenticated;
revoke execute on function public.remove_push_token(text, text)     from public, anon, authenticated;
revoke execute on function public.handle_push_on_message()          from public, anon, authenticated;

grant execute on function public.register_push_token(text, text)    to authenticated;
grant execute on function public.unregister_push_token(text)        to authenticated;
grant execute on function public.push_payload(uuid, text)           to service_role;
grant execute on function public.remove_push_token(text, text)      to service_role;
