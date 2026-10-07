-- =====================================================================
-- MayChat - migration 28: photo albums, and notifications for people who
-- are mentioned in a group they muted
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Run migrations 22 and 27 BEFORE this file.
--
-- CHANGES:
--   1. The two rules (check constraints) about the kind of a message now
--      also ALLOW 'album' (several pictures in one message). Everything
--      they allowed before is still allowed.
--   2. The rule about the length of "extra" goes from 300 to 4000
--      characters (an album keeps the list of its pictures there).
--   3. push_payload (migrations 21/22): its part for one-to-one messages
--      is kept word for word. In the part for group messages, a member who
--      muted the group is now STILL notified when the message mentions
--      them ("@their name") or everybody ("@Tất cả"). A member who
--      switched ALL message notifications off is still not notified.
-- Nothing is added and no other function is touched.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. ALBUMS
-- An album is a message with kind 'album'. media_path is its first
-- picture; "extra" lists the other pictures, one storage path per line.
-- All of them are in the folder of the conversation (or group), like any
-- other picture.
-- ---------------------------------------------------------------------

alter table public.messages drop constraint if exists messages_extra_check;
alter table public.messages add constraint messages_extra_check
    check (extra is null or char_length(extra) <= 4000);
alter table public.group_messages drop constraint if exists group_messages_extra_check;
alter table public.group_messages add constraint group_messages_extra_check
    check (extra is null or char_length(extra) <= 4000);

alter table public.messages drop constraint if exists messages_kind_check;
alter table public.messages add constraint messages_kind_check check (
    kind in ('text', 'image', 'voice', 'file', 'system', 'sticker', 'location', 'contact', 'album')
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

alter table public.group_messages drop constraint if exists group_messages_kind_check;
alter table public.group_messages add constraint group_messages_kind_check check (
    kind in ('text', 'image', 'voice', 'file', 'system', 'sticker', 'location', 'contact', 'album')
    and (
        (kind in ('text', 'system', 'sticker', 'location', 'contact') and media_path is null)
        or (kind not in ('text', 'system', 'sticker', 'location', 'contact')
            and media_path is not null
            and media_path like group_id::text || '/%')
    )
);


-- ---------------------------------------------------------------------
-- 2. A MENTION GETS THROUGH A MUTED GROUP
-- Same function as in migration 22, with one changed check (marked NEW).
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
      -- Nor people who muted this group - EXCEPT when this message
      -- mentions them ("@their name") or everybody ("@Tất cả"). (NEW)
      and (
          not exists (
              select 1 from public.group_prefs p
              where p.user_id = t.user_id
                and p.group_id = v_gmsg.group_id
                and p.muted
          )
          or position('@Tất cả' in v_gmsg.content) > 0
          or exists (
              select 1 from public.profiles pr
              where pr.id = t.user_id
                and char_length(pr.display_name) > 0
                and position('@' || pr.display_name in v_gmsg.content) > 0
          )
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

revoke execute on function public.push_payload(uuid, text) from public, anon, authenticated;
grant execute on function public.push_payload(uuid, text) to service_role;
