-- =====================================================================
-- MayChat - migration 27: hearts that count up, stickers, location,
-- contact cards, and what the app needs to draw its own notifications
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Run migrations 10, 21 and 22 BEFORE this file.
--
-- ADDS: one column "count" to each of the two reaction tables, one column
-- "extra" to each of the two message tables, and five functions.
--
-- CHANGES two existing rules (check constraints), only to ALLOW three more
-- kinds of message: 'sticker', 'location' and 'contact'. Like a text, they
-- have no file. Everything the rules allowed before is still allowed.
-- No existing function is changed.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. REACTIONS THAT COUNT UP (tap the heart several times, like Zalo)
-- ---------------------------------------------------------------------

alter table public.message_reactions
    add column if not exists count integer not null default 1;
alter table public.group_message_reactions
    add column if not exists count integer not null default 1;

-- Tap an emoji on a message of one of MY conversations:
--   * the same emoji as my current one -> one more (up to 999);
--   * another emoji, or no reaction yet -> that emoji, counted once.
-- (Removing my reaction still goes through set_reaction with an empty
-- emoji, as before.)
create or replace function public.add_reaction(p_message uuid, p_emoji text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_emoji text := left(coalesce(p_emoji, ''), 16);
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if v_emoji = '' then raise exception 'not_allowed'; end if;
    if not exists (
        select 1
        from public.messages m
        join public.conversations c on c.id = m.conversation_id
        where m.id = p_message
          and v_me in (c.user_a, c.user_b)
          and m.recalled_at is null
    ) then
        raise exception 'not_allowed';
    end if;

    insert into public.message_reactions as r (message_id, user_id, emoji, count)
    values (p_message, v_me, v_emoji, 1)
    on conflict (message_id, user_id) do update
        set count = case when r.emoji = excluded.emoji then least(r.count + 1, 999) else 1 end,
            emoji = excluded.emoji,
            created_at = now();
end;
$$;

-- All reactions in one conversation I am a member of, with their counts.
create or replace function public.conversation_reactions_v2(p_conversation uuid)
returns table (message_id uuid, user_id uuid, emoji text, count integer)
language sql
stable
security definer
set search_path = ''
as $$
    select r.message_id, r.user_id, r.emoji, r.count
    from public.message_reactions r
    join public.messages m on m.id = r.message_id
    join public.conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation
      and auth.uid() in (c.user_a, c.user_b);
$$;

-- The same two functions for groups.
create or replace function public.add_group_reaction(p_message uuid, p_emoji text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_emoji text := left(coalesce(p_emoji, ''), 16);
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if v_emoji = '' then raise exception 'not_allowed'; end if;
    if not exists (
        select 1 from public.group_messages m
        where m.id = p_message
          and m.recalled_at is null
          and m.kind <> 'system'
          and public.is_group_member(m.group_id)
    ) then
        raise exception 'not_allowed';
    end if;

    insert into public.group_message_reactions as r (message_id, user_id, emoji, count)
    values (p_message, v_me, v_emoji, 1)
    on conflict (message_id, user_id) do update
        set count = case when r.emoji = excluded.emoji then least(r.count + 1, 999) else 1 end,
            emoji = excluded.emoji,
            created_at = now();
end;
$$;

create or replace function public.group_reactions_v2(p_group uuid)
returns table (message_id uuid, user_id uuid, emoji text, count integer)
language sql
stable
security definer
set search_path = ''
as $$
    select r.message_id, r.user_id, r.emoji, r.count
    from public.group_message_reactions r
    join public.group_messages m on m.id = r.message_id
    where m.group_id = p_group
      and public.is_group_member(p_group);
$$;


-- ---------------------------------------------------------------------
-- 2. STICKERS, LOCATION, CONTACT CARDS
-- A new column "extra" carries the small piece of data such a message
-- needs: which sticker, the coordinates, or whose card it is.
-- ---------------------------------------------------------------------

alter table public.messages       add column if not exists extra text;
alter table public.group_messages add column if not exists extra text;

alter table public.messages drop constraint if exists messages_extra_check;
alter table public.messages add constraint messages_extra_check
    check (extra is null or char_length(extra) <= 300);
alter table public.group_messages drop constraint if exists group_messages_extra_check;
alter table public.group_messages add constraint group_messages_extra_check
    check (extra is null or char_length(extra) <= 300);

-- The app may fill in the new column when sending.
grant insert (extra) on public.messages       to authenticated;
grant insert (extra) on public.group_messages to authenticated;

-- One-to-one messages: the same rule as in migration 14, with the three
-- new kinds added next to 'text' (no file).
alter table public.messages drop constraint if exists messages_kind_check;
alter table public.messages add constraint messages_kind_check check (
    kind in ('text', 'image', 'voice', 'file', 'system', 'sticker', 'location', 'contact')
    and (
        (kind in ('text', 'system', 'sticker', 'location', 'contact') and media_path is null)
        or (kind not in ('text', 'system', 'sticker', 'location', 'contact')
            and media_path is not null
            and media_path like conversation_id::text || '/%')
    )
    and (duration_ms is null or duration_ms between 0 and 600000)
    and (file_name is null or char_length(file_name) between 1 and 200)
    and (file_size is null or file_size between 0 and 5242880)
);

-- Group messages: migration 21 wrote its two rules about "kind" without
-- names, so they are looked up here, removed, and replaced by one rule
-- with a name. (The rules about other columns are not touched.)
do $$
declare
    v_name text;
begin
    for v_name in
        select c.conname
        from pg_constraint c
        where c.conrelid = 'public.group_messages'::regclass
          and c.contype = 'c'
          and pg_get_constraintdef(c.oid) ilike '%kind%'
    loop
        execute format('alter table public.group_messages drop constraint %I', v_name);
    end loop;
end;
$$;

alter table public.group_messages add constraint group_messages_kind_check check (
    kind in ('text', 'image', 'voice', 'file', 'system', 'sticker', 'location', 'contact')
    and (
        (kind in ('text', 'system', 'sticker', 'location', 'contact') and media_path is null)
        or (kind not in ('text', 'system', 'sticker', 'location', 'contact')
            and media_path is not null
            and media_path like group_id::text || '/%')
    )
);


-- ---------------------------------------------------------------------
-- 3. WHAT THE APP NEEDS TO DRAW A NOTIFICATION ITSELF
-- Used only by the notification function (clever-worker): who sent the
-- message, their picture, the plain text, and whether it is a group.
-- ---------------------------------------------------------------------

create or replace function public.push_message_info(p_message_id uuid, p_secret text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_msg   public.messages%rowtype;
    v_gmsg  public.group_messages%rowtype;
    v_name  text;
    v_path  text;
    v_group text;
begin
    if not exists (select 1 from public.push_config where secret = p_secret) then
        return null;
    end if;

    select * into v_msg from public.messages where id = p_message_id;
    if found then
        select display_name, avatar_path into v_name, v_path
        from public.profiles where id = v_msg.sender_id;
        return jsonb_build_object(
            'is_group', false,
            'sender_name', coalesce(v_name, 'MayChat'),
            'avatar_path', nullif(v_path, ''),
            'text', left(v_msg.content, 120)
        );
    end if;

    select * into v_gmsg from public.group_messages where id = p_message_id;
    if not found then return null; end if;

    select display_name, avatar_path into v_name, v_path
    from public.profiles where id = v_gmsg.sender_id;
    select name into v_group from public.groups where id = v_gmsg.group_id;
    return jsonb_build_object(
        'is_group', true,
        'group_name', coalesce(v_group, 'Nhóm'),
        'sender_name', coalesce(v_name, 'Ai đó'),
        'avatar_path', nullif(v_path, ''),
        'text', left(v_gmsg.content, 120)
    );
end;
$$;


-- ---------------------------------------------------------------------
-- 4. PERMISSIONS
-- ---------------------------------------------------------------------

revoke execute on function public.add_reaction(uuid, text)              from public, anon;
revoke execute on function public.conversation_reactions_v2(uuid)       from public, anon;
revoke execute on function public.add_group_reaction(uuid, text)        from public, anon;
revoke execute on function public.group_reactions_v2(uuid)              from public, anon;
revoke execute on function public.push_message_info(uuid, text)         from public, anon, authenticated;

grant execute on function public.add_reaction(uuid, text)               to authenticated;
grant execute on function public.conversation_reactions_v2(uuid)        to authenticated;
grant execute on function public.add_group_reaction(uuid, text)         to authenticated;
grant execute on function public.group_reactions_v2(uuid)               to authenticated;
grant execute on function public.push_message_info(uuid, text)          to service_role;
