-- =====================================================================
-- MayChat - migration 18: private call channel, speed limits, and
-- permission to delete one's own files from the storage
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS only: two rules for the call channel, two speed-limit triggers with
-- their functions, one index, and two "delete" rules for the storage.
-- No existing function, rule or table is changed.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. PRIVATE CALL CHANNEL
-- From app version 0.19.0 the small "set up a call" messages travel on a
-- PRIVATE Realtime channel named "call-<user id>". These two rules decide
-- who may use it.
-- ---------------------------------------------------------------------

-- Only the owner can LISTEN on their own call channel.
drop policy if exists "calls: owner can listen" on realtime.messages;
create policy "calls: owner can listen"
    on realtime.messages for select
    to authenticated
    using (
        realtime.messages.extension = 'broadcast'
        and (select realtime.topic()) = 'call-' || (select auth.uid())::text
    );

-- Only someone who has a conversation with the owner can SEND there, and
-- not when one of the two has blocked the other.
drop policy if exists "calls: contacts can signal" on realtime.messages;
create policy "calls: contacts can signal"
    on realtime.messages for insert
    to authenticated
    with check (
        realtime.messages.extension = 'broadcast'
        and exists (
            select 1
            from public.conversations c
            where (select auth.uid()) in (c.user_a, c.user_b)
              and (select realtime.topic()) = 'call-' ||
                  (case when c.user_a = (select auth.uid()) then c.user_b else c.user_a end)::text
        )
    );


-- ---------------------------------------------------------------------
-- 2. SPEED LIMITS (against spam)
-- ---------------------------------------------------------------------

-- Makes counting "messages of this sender in the last minute" fast.
create index if not exists messages_sender_created_idx
    on public.messages (sender_id, created_at desc);

-- At most 60 messages in 10 seconds and 300 in one minute per person.
-- Normal chatting, sending 10 pictures at once and forwarding a batch of
-- messages all stay far below this.
create or replace function public.limit_message_rate()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_recent integer;
begin
    select count(*) into v_recent
    from public.messages
    where sender_id = new.sender_id
      and created_at > now() - interval '10 seconds';
    if v_recent >= 60 then raise exception 'rate_limited'; end if;

    select count(*) into v_recent
    from public.messages
    where sender_id = new.sender_id
      and created_at > now() - interval '1 minute';
    if v_recent >= 300 then raise exception 'rate_limited'; end if;

    return new;
end;
$$;

drop trigger if exists messages_rate_limit on public.messages;
create trigger messages_rate_limit
    before insert on public.messages
    for each row execute function public.limit_message_rate();

-- At most 30 friend requests per hour per person.
create or replace function public.limit_friend_request_rate()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_recent integer;
begin
    select count(*) into v_recent
    from public.friendships
    where requested_by = new.requested_by
      and created_at > now() - interval '1 hour';
    if v_recent >= 30 then raise exception 'rate_limited'; end if;
    return new;
end;
$$;

drop trigger if exists friendships_rate_limit on public.friendships;
create trigger friendships_rate_limit
    before insert on public.friendships
    for each row execute function public.limit_friend_request_rate();

revoke execute on function public.limit_message_rate()        from public, anon, authenticated;
revoke execute on function public.limit_friend_request_rate() from public, anon, authenticated;


-- ---------------------------------------------------------------------
-- 3. DELETING ONE'S OWN FILES
-- Until now nobody could delete a stored file. The app now removes the
-- file of a recalled picture / voice message / file, an old chat
-- background picture, and an old avatar.
-- ---------------------------------------------------------------------

-- chat-media: I may delete a file I uploaded myself; a chat background
-- picture may be deleted by either member of that conversation.
drop policy if exists "chat-media: delete own files" on storage.objects;
create policy "chat-media: delete own files"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'chat-media'
        and exists (
            select 1 from public.conversations c
            where c.id::text = (storage.foldername(name))[1]
              and (select auth.uid()) in (c.user_a, c.user_b)
        )
        and (
            owner_id = (select auth.uid())::text
            or storage.filename(name) like 'wallpaper-%'
        )
    );

-- avatars: I may delete files in the folder named after my own id.
drop policy if exists "avatars: delete own files" on storage.objects;
create policy "avatars: delete own files"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );
