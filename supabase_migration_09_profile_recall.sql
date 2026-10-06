-- =====================================================================
-- MayChat - migration 09: avatars, edit profile, recall and hide messages
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS only: two new columns, one new table, one new storage bucket and
-- five new functions. No existing column, rule or function is changed.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. NEW COLUMNS (existing rows simply get "empty" in them)
-- ---------------------------------------------------------------------

-- Where the user's avatar picture is in Storage (empty = no avatar).
alter table public.profiles add column if not exists avatar_path text;

-- When the sender took the message back (empty = normal message).
alter table public.messages add column if not exists recalled_at timestamptz;


-- ---------------------------------------------------------------------
-- 2. EDIT MY PROFILE
-- ---------------------------------------------------------------------

-- Changes my display name and/or my avatar.
--   p_avatar_path = null  -> keep the current avatar
--   p_avatar_path = ''    -> remove the avatar
--   otherwise it must be a file inside MY folder of the "avatars" bucket.
create or replace function public.update_my_profile(p_display_name text, p_avatar_path text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_name text := trim(coalesce(p_display_name, ''));
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if char_length(v_name) < 1 or char_length(v_name) > 50 then
        raise exception 'invalid_display_name';
    end if;
    if p_avatar_path is not null and p_avatar_path <> ''
       and p_avatar_path not like v_me::text || '/%' then
        raise exception 'invalid_avatar_path';
    end if;

    update public.profiles
    set display_name = v_name,
        avatar_path = case
            when p_avatar_path is null then avatar_path
            when p_avatar_path = '' then null
            else p_avatar_path
        end
    where id = v_me;
end;
$$;


-- ---------------------------------------------------------------------
-- 3. AVATAR PICTURES: a private bucket, files at most 1 MB
-- Files are stored as  <user id>/<random name>.jpg
-- ---------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit)
values ('avatars', 'avatars', false, 1048576)
on conflict (id) do update
    set public = false,
        file_size_limit = excluded.file_size_limit;

-- Every logged-in user may look at avatars (they are meant to be seen).
drop policy if exists "avatars: logged-in users can read" on storage.objects;
create policy "avatars: logged-in users can read"
    on storage.objects for select
    to authenticated
    using (bucket_id = 'avatars');

-- A user may upload only into the folder named after their own id.
drop policy if exists "avatars: upload into own folder" on storage.objects;
create policy "avatars: upload into own folder"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );


-- ---------------------------------------------------------------------
-- 4. RECALL A MESSAGE (take it back for both people)
-- ---------------------------------------------------------------------

-- Only the sender can recall. The text is replaced, so the original
-- content is gone from the database for both people.
create or replace function public.recall_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me  uuid := auth.uid();
    v_msg public.messages%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    select * into v_msg from public.messages where id = p_message;
    if not found or v_msg.sender_id <> v_me then
        raise exception 'not_allowed';
    end if;
    if v_msg.recalled_at is not null then return; end if;

    update public.messages
    set content = 'Tin nhắn đã được thu hồi',
        kind = 'text',
        media_path = null,
        duration_ms = null,
        recalled_at = now()
    where id = p_message;

    -- If it was the newest message, fix the preview in the conversation list.
    update public.conversations
    set last_message_text = 'Tin nhắn đã được thu hồi'
    where id = v_msg.conversation_id
      and last_sender_id = v_msg.sender_id
      and last_message_at = v_msg.created_at;
end;
$$;


-- ---------------------------------------------------------------------
-- 5. HIDE A MESSAGE ON MY SIDE ONLY ("delete for me")
-- ---------------------------------------------------------------------

create table if not exists public.hidden_messages (
    user_id     uuid not null references public.profiles (id) on delete cascade,
    message_id  uuid not null references public.messages (id) on delete cascade,
    hidden_at   timestamptz not null default now(),
    primary key (user_id, message_id)
);

alter table public.hidden_messages enable row level security;
revoke all on public.hidden_messages from anon, authenticated;

-- I can hide any message of a conversation I am a member of.
create or replace function public.hide_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not exists (
        select 1
        from public.messages m
        join public.conversations c on c.id = m.conversation_id
        where m.id = p_message and v_me in (c.user_a, c.user_b)
    ) then
        raise exception 'not_allowed';
    end if;

    insert into public.hidden_messages (user_id, message_id)
    values (v_me, p_message)
    on conflict do nothing;
end;
$$;

-- Ids of the messages I have hidden in one conversation.
create or replace function public.hidden_message_ids(p_conversation uuid)
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
    select h.message_id
    from public.hidden_messages h
    join public.messages m on m.id = h.message_id
    where h.user_id = auth.uid()
      and m.conversation_id = p_conversation;
$$;


-- ---------------------------------------------------------------------
-- 6. PERMISSIONS
-- ---------------------------------------------------------------------

revoke execute on function public.update_my_profile(text, text)  from public, anon;
revoke execute on function public.recall_message(uuid)           from public, anon;
revoke execute on function public.hide_message(uuid)             from public, anon;
revoke execute on function public.hidden_message_ids(uuid)       from public, anon;

grant execute on function public.update_my_profile(text, text)   to authenticated;
grant execute on function public.recall_message(uuid)            to authenticated;
grant execute on function public.hide_message(uuid)              to authenticated;
grant execute on function public.hidden_message_ids(uuid)        to authenticated;
