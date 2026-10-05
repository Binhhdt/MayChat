-- =====================================================================
-- MayChat - migration 03: image messages and voice messages
--
-- Run AFTER supabase_schema.sql AND supabase_migration_02_friends.sql.
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- Uses Supabase Storage on the free plan (1 GB). No payment method needed.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. MESSAGES: remember what kind of message it is and where its file is
-- ---------------------------------------------------------------------

alter table public.messages add column if not exists kind text not null default 'text';
alter table public.messages add column if not exists media_path text;
alter table public.messages add column if not exists duration_ms integer;

-- A text message has no file. An image/voice message must point to a file
-- inside the folder of ITS OWN conversation, so a message can never link
-- to a file from somebody else's chat.
do $$
begin
    alter table public.messages add constraint messages_kind_check check (
        kind in ('text', 'image', 'voice')
        and (
            (kind = 'text' and media_path is null)
            or (kind <> 'text'
                and media_path is not null
                and media_path like conversation_id::text || '/%')
        )
        and (duration_ms is null or duration_ms between 0 and 600000)
    );
exception
    when duplicate_object then null;
end;
$$;

-- The app may now also fill in these three columns when sending.
grant insert (kind, media_path, duration_ms) on public.messages to authenticated;


-- ---------------------------------------------------------------------
-- 2. STORAGE: one PRIVATE bucket, files at most 5 MB each
-- Files are stored as  <conversation id>/<random name>.jpg  or  .m4a
-- ---------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit)
values ('chat-media', 'chat-media', false, 5242880)
on conflict (id) do update
    set public = false,
        file_size_limit = excluded.file_size_limit;

-- Only the two members of a conversation can download its files.
drop policy if exists "chat-media: members can read" on storage.objects;
create policy "chat-media: members can read"
    on storage.objects for select
    to authenticated
    using (
        bucket_id = 'chat-media'
        and exists (
            select 1 from public.conversations c
            where c.id::text = (storage.foldername(name))[1]
              and (select auth.uid()) in (c.user_a, c.user_b)
        )
    );

-- Only members can upload into their conversation's folder, and not when
-- one of them has blocked the other.
drop policy if exists "chat-media: members can upload" on storage.objects;
create policy "chat-media: members can upload"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'chat-media'
        and exists (
            select 1 from public.conversations c
            where c.id::text = (storage.foldername(name))[1]
              and (select auth.uid()) in (c.user_a, c.user_b)
              and not public.is_blocked_between(c.user_a, c.user_b)
        )
    );

-- There is no update or delete policy on purpose: nobody can overwrite or
-- delete a file through the app.
