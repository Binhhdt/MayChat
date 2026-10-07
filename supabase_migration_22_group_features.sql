-- =====================================================================
-- MayChat - migration 22: the features of one-to-one chats, for groups
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Run supabase_migration_17_search.sql and supabase_migration_21_groups.sql
-- BEFORE this file.
--
-- ADDS: four columns to group_messages, two columns to groups, three
-- tables, their rules, eleven functions and one storage rule.
-- The tables of the one-to-one chats are not touched.
--
-- CHANGES two existing functions, both from migration 21:
--   1. rename_group: from now on ONLY THE GROUP LEADER may rename the
--      group (before: every member).
--   2. push_payload: its part for one-to-one messages is kept word for
--      word; the part for group messages now also leaves out members who
--      switched the notifications of that group off.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. ONLY THE GROUP LEADER MAY RENAME THE GROUP
-- ---------------------------------------------------------------------

create or replace function public.rename_group(p_group uuid, p_name text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_name text := trim(coalesce(p_name, ''));
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;
    if not exists (select 1 from public.groups where id = p_group and owner_id = v_me) then
        raise exception 'only_leader';
    end if;
    if char_length(v_name) < 1 or char_length(v_name) > 60 then
        raise exception 'invalid_group_name';
    end if;

    update public.groups set name = v_name where id = p_group and name <> v_name;
    if found then
        insert into public.group_messages (group_id, sender_id, content, kind)
        values (p_group, v_me, 'đã đổi tên nhóm thành "' || v_name || '"', 'system');
    end if;
end;
$$;


-- ---------------------------------------------------------------------
-- 2. NEW COLUMNS
-- ---------------------------------------------------------------------

-- Take back a message; answer (quote) a message.
alter table public.group_messages add column if not exists recalled_at timestamptz;
alter table public.group_messages add column if not exists reply_to_id uuid
    references public.group_messages (id) on delete set null;
alter table public.group_messages add column if not exists reply_preview text;
alter table public.group_messages add column if not exists reply_sender_id uuid;

alter table public.group_messages drop constraint if exists group_messages_reply_preview_check;
alter table public.group_messages add constraint group_messages_reply_preview_check
    check (reply_preview is null or char_length(reply_preview) <= 200);

-- The app may fill in the three reply columns when sending (never recalled_at).
grant insert (reply_to_id, reply_preview, reply_sender_id) on public.group_messages to authenticated;

-- The pinned message and the background of a group, same for all members.
alter table public.groups add column if not exists pinned_message_id uuid
    references public.group_messages (id) on delete set null;
alter table public.groups add column if not exists wallpaper text;


-- ---------------------------------------------------------------------
-- 3. TAKE BACK (for everyone) AND DELETE ON MY SIDE
-- ---------------------------------------------------------------------

-- Only the sender can take a message back. Its text and file disappear
-- for every member.
create or replace function public.recall_group_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me  uuid := auth.uid();
    v_msg public.group_messages%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    select * into v_msg from public.group_messages where id = p_message;
    if not found or v_msg.sender_id <> v_me or v_msg.kind = 'system' then
        raise exception 'not_allowed';
    end if;
    if not public.is_group_member(v_msg.group_id) then raise exception 'not_allowed'; end if;
    if v_msg.recalled_at is not null then return; end if;

    update public.group_messages
    set content = 'Tin nhắn đã được thu hồi',
        kind = 'text',
        media_path = null,
        duration_ms = null,
        file_name = null,
        file_size = null,
        recalled_at = now()
    where id = p_message;

    -- If it was the newest message, fix the preview in the list.
    update public.groups
    set last_message_text = 'Tin nhắn đã được thu hồi'
    where id = v_msg.group_id
      and last_sender_id = v_msg.sender_id
      and last_message_at = v_msg.created_at;

    -- Replies that quote this message must not keep its text.
    update public.group_messages
    set reply_preview = 'Tin nhắn đã được thu hồi'
    where reply_to_id = p_message;

    -- A recalled message has no reactions and is not pinned.
    delete from public.group_message_reactions where message_id = p_message;
    update public.groups set pinned_message_id = null
    where id = v_msg.group_id and pinned_message_id = p_message;
end;
$$;

-- Messages I removed from MY screen only.
create table if not exists public.group_hidden_messages (
    user_id     uuid not null references public.profiles (id) on delete cascade,
    message_id  uuid not null references public.group_messages (id) on delete cascade,
    hidden_at   timestamptz not null default now(),
    primary key (user_id, message_id)
);

alter table public.group_hidden_messages enable row level security;
revoke all on public.group_hidden_messages from anon, authenticated;

create or replace function public.hide_group_message(p_message uuid)
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
        select 1 from public.group_messages m
        where m.id = p_message and public.is_group_member(m.group_id)
    ) then
        raise exception 'not_allowed';
    end if;

    insert into public.group_hidden_messages (user_id, message_id)
    values (v_me, p_message)
    on conflict do nothing;
end;
$$;

-- Ids of the messages I have hidden in one group.
create or replace function public.hidden_group_message_ids(p_group uuid)
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
    select h.message_id
    from public.group_hidden_messages h
    join public.group_messages m on m.id = h.message_id
    where h.user_id = auth.uid()
      and m.group_id = p_group;
$$;


-- ---------------------------------------------------------------------
-- 4. REACTIONS
-- ---------------------------------------------------------------------

create table if not exists public.group_message_reactions (
    message_id  uuid not null references public.group_messages (id) on delete cascade,
    user_id     uuid not null references public.profiles (id) on delete cascade,
    emoji       text not null check (char_length(emoji) between 1 and 16),
    created_at  timestamptz not null default now(),
    primary key (message_id, user_id)
);

alter table public.group_message_reactions enable row level security;
revoke all on public.group_message_reactions from anon, authenticated;

-- Sets, changes or removes MY reaction. p_emoji empty = remove it.
create or replace function public.set_group_reaction(p_message uuid, p_emoji text)
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
        select 1 from public.group_messages m
        where m.id = p_message
          and m.recalled_at is null
          and m.kind <> 'system'
          and public.is_group_member(m.group_id)
    ) then
        raise exception 'not_allowed';
    end if;

    if p_emoji is null or p_emoji = '' then
        delete from public.group_message_reactions
        where message_id = p_message and user_id = v_me;
    else
        insert into public.group_message_reactions (message_id, user_id, emoji)
        values (p_message, v_me, left(p_emoji, 16))
        on conflict (message_id, user_id) do update
            set emoji = excluded.emoji,
                created_at = now();
    end if;
end;
$$;

-- All reactions in one group I am a member of.
create or replace function public.group_reactions(p_group uuid)
returns table (message_id uuid, user_id uuid, emoji text)
language sql
stable
security definer
set search_path = ''
as $$
    select r.message_id, r.user_id, r.emoji
    from public.group_message_reactions r
    join public.group_messages m on m.id = r.message_id
    where m.group_id = p_group
      and public.is_group_member(p_group);
$$;


-- ---------------------------------------------------------------------
-- 5. PINNED MESSAGE (every member may pin and unpin)
-- ---------------------------------------------------------------------

create or replace function public.pin_group_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_group uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    select m.group_id into v_group
    from public.group_messages m
    where m.id = p_message
      and m.recalled_at is null
      and m.kind <> 'system'
      and public.is_group_member(m.group_id);

    if v_group is null then raise exception 'not_allowed'; end if;

    update public.groups set pinned_message_id = p_message where id = v_group;
end;
$$;

create or replace function public.unpin_group_message(p_group uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;
    update public.groups set pinned_message_id = null where id = p_group;
end;
$$;

create or replace function public.group_pinned_message(p_group uuid)
returns table (message_id uuid, content text, created_at timestamptz, kind text)
language sql
stable
security definer
set search_path = ''
as $$
    select m.id, m.content, m.created_at, m.kind
    from public.groups g
    join public.group_messages m on m.id = g.pinned_message_id
    where g.id = p_group
      and public.is_group_member(p_group);
$$;


-- ---------------------------------------------------------------------
-- 6. BACKGROUND OF THE GROUP (every member may change it; a notice says who)
-- ---------------------------------------------------------------------

create or replace function public.set_group_wallpaper(p_group uuid, p_value text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_value text := nullif(trim(coalesce(p_value, '')), '');
    v_old   text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;

    if v_value is not null
       and v_value not in ('mint', 'peach', 'sky', 'lilac', 'sand')
       and v_value not like 'img:' || p_group::text || '/%' then
        raise exception 'invalid_wallpaper';
    end if;

    select wallpaper into v_old from public.groups where id = p_group;
    if not found then raise exception 'not_allowed'; end if;

    -- Nothing to do when the same background is chosen again.
    if v_old is not distinct from v_value then return; end if;

    update public.groups set wallpaper = v_value where id = p_group;

    insert into public.group_messages (group_id, sender_id, content, kind)
    values (
        p_group,
        v_me,
        case when v_value is null then 'đã xóa hình nền' else 'đã thay đổi hình nền' end,
        'system'
    );
end;
$$;


-- ---------------------------------------------------------------------
-- 7. MY OWN SETTINGS FOR ONE GROUP (the other members never see them)
-- ---------------------------------------------------------------------

create table if not exists public.group_prefs (
    user_id     uuid not null references public.profiles (id) on delete cascade,
    group_id    uuid not null references public.groups (id) on delete cascade,
    pinned_at   timestamptz,                     -- set = pinned at the top of my list
    muted       boolean not null default false,  -- true = no notifications of this group
    cleared_at  timestamptz,                     -- I deleted everything up to this moment
    primary key (user_id, group_id)
);

alter table public.group_prefs enable row level security;
revoke all on public.group_prefs from anon, authenticated;
grant select on public.group_prefs to authenticated;

drop policy if exists "group_prefs: read own" on public.group_prefs;
create policy "group_prefs: read own"
    on public.group_prefs for select
    to authenticated
    using (user_id = (select auth.uid()));

-- p_pinned / p_muted: null = leave as it is.  p_clear: true = delete the
-- history on my side (messages up to now are hidden for me only).
create or replace function public.set_group_pref(
    p_group uuid,
    p_pinned boolean,
    p_muted boolean,
    p_clear boolean
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;

    insert into public.group_prefs (user_id, group_id)
    values (v_me, p_group)
    on conflict do nothing;

    update public.group_prefs
    set pinned_at = case
            when p_pinned is null then pinned_at
            when p_pinned then now()
            else null
        end,
        muted = coalesce(p_muted, muted),
        cleared_at = case when coalesce(p_clear, false) then now() else cleared_at end
    where user_id = v_me and group_id = p_group;

    -- Deleted messages must not stay counted as unread.
    if coalesce(p_clear, false) then
        update public.group_members
        set last_read_at = now()
        where group_id = p_group and user_id = v_me;
    end if;
end;
$$;


-- ---------------------------------------------------------------------
-- 8. SEARCH THAT IGNORES VIETNAMESE ACCENTS (uses vn_fold of migration 17)
-- ---------------------------------------------------------------------

create or replace function public.search_group_messages(
    p_group uuid,
    p_query text,
    p_after timestamptz
)
returns setof public.group_messages
language sql
stable
security definer
set search_path = ''
as $$
    select m.*
    from public.group_messages m
    where m.group_id = p_group
      and public.is_group_member(p_group)
      and m.kind = 'text'
      and m.recalled_at is null
      and (p_after is null or m.created_at > p_after)
      and char_length(trim(coalesce(p_query, ''))) >= 2
      and position(public.vn_fold(trim(p_query)) in public.vn_fold(m.content)) > 0
    order by m.created_at desc
    limit 300;
$$;


-- ---------------------------------------------------------------------
-- 9. STORAGE: delete files of a group that are no longer needed
-- (my own files after taking a message back; a replaced background)
-- ---------------------------------------------------------------------

drop policy if exists "chat-media: group members delete own files" on storage.objects;
create policy "chat-media: group members delete own files"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'chat-media'
        and exists (
            select 1 from public.group_members gm
            where gm.group_id::text = (storage.foldername(name))[1]
              and gm.user_id = (select auth.uid())
        )
        and (
            owner_id = (select auth.uid())::text
            or storage.filename(name) like 'wallpaper-%'
        )
    );


-- ---------------------------------------------------------------------
-- 10. NOTIFICATIONS RESPECT "MUTE THIS GROUP"
-- Same function as in migration 21. PART 1 is unchanged; in PART 2 one
-- check is added (marked NEW).
-- ---------------------------------------------------------------------

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
    v_gmsg      public.group_messages%rowtype;
    v_group     public.groups%rowtype;
begin
    if not exists (select 1 from public.push_config where secret = p_secret) then
        return null;
    end if;

    select * into v_msg from public.messages where id = p_message_id;
    if found then
        -- ---------------- PART 1: one-to-one message (unchanged) ----------------
        if v_msg.read_at is not null then
            return null;   -- already read: nothing to notify
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
    end if;

    -- ---------------- PART 2: group message ----------------
    select * into v_gmsg from public.group_messages where id = p_message_id;
    if not found then return null; end if;

    select * into v_group from public.groups where id = v_gmsg.group_id;
    if not found then return null; end if;

    -- Phones of every member except the sender, leaving out people who
    -- switched message notifications off.
    select coalesce(jsonb_agg(t.token), '[]'::jsonb) into v_tokens
    from public.push_tokens t
    join public.group_members gm on gm.user_id = t.user_id
    where gm.group_id = v_gmsg.group_id
      and t.user_id <> v_gmsg.sender_id
      and not exists (
          select 1 from public.user_settings s
          where s.user_id = t.user_id and s.mute_messages
      )
      -- NEW: nor people who muted this group.
      and not exists (
          select 1 from public.group_prefs p
          where p.user_id = t.user_id
            and p.group_id = v_gmsg.group_id
            and p.muted
      );

    select display_name into v_name from public.profiles where id = v_gmsg.sender_id;

    return jsonb_build_object(
        'tokens', v_tokens,
        'title', v_group.name,
        'body', coalesce(v_name, 'Ai đó') || ': ' || left(v_gmsg.content, 100),
        'conversation_id', v_gmsg.group_id,
        'sender_id', v_gmsg.sender_id,
        'unread', 1
    );
end;
$$;


-- ---------------------------------------------------------------------
-- 11. PERMISSIONS
-- ---------------------------------------------------------------------

revoke execute on function public.rename_group(uuid, text) from public, anon;
revoke execute on function public.recall_group_message(uuid) from public, anon;
revoke execute on function public.hide_group_message(uuid) from public, anon;
revoke execute on function public.hidden_group_message_ids(uuid) from public, anon;
revoke execute on function public.set_group_reaction(uuid, text) from public, anon;
revoke execute on function public.group_reactions(uuid) from public, anon;
revoke execute on function public.pin_group_message(uuid) from public, anon;
revoke execute on function public.unpin_group_message(uuid) from public, anon;
revoke execute on function public.group_pinned_message(uuid) from public, anon;
revoke execute on function public.set_group_wallpaper(uuid, text) from public, anon;
revoke execute on function public.set_group_pref(uuid, boolean, boolean, boolean) from public, anon;
revoke execute on function public.search_group_messages(uuid, text, timestamptz) from public, anon;
revoke execute on function public.push_payload(uuid, text) from public, anon, authenticated;

grant execute on function public.rename_group(uuid, text) to authenticated;
grant execute on function public.recall_group_message(uuid) to authenticated;
grant execute on function public.hide_group_message(uuid) to authenticated;
grant execute on function public.hidden_group_message_ids(uuid) to authenticated;
grant execute on function public.set_group_reaction(uuid, text) to authenticated;
grant execute on function public.group_reactions(uuid) to authenticated;
grant execute on function public.pin_group_message(uuid) to authenticated;
grant execute on function public.unpin_group_message(uuid) to authenticated;
grant execute on function public.group_pinned_message(uuid) to authenticated;
grant execute on function public.set_group_wallpaper(uuid, text) to authenticated;
grant execute on function public.set_group_pref(uuid, boolean, boolean, boolean) to authenticated;
grant execute on function public.search_group_messages(uuid, text, timestamptz) to authenticated;
grant execute on function public.push_payload(uuid, text) to service_role;
