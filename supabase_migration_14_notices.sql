-- =====================================================================
-- MayChat - migration 14: a line in the chat saying who changed the
-- chat background
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- CHANGES two existing things, both only for this feature:
--   1. the rule "which kinds of message exist" also accepts 'system'
--      (a notice written by the server, shown centered in the chat);
--   2. set_wallpaper (from migration 13) now also writes that notice.
-- ADDS one rule so that the app itself can never write such notices.
-- =====================================================================

-- Same rule as in migration 12, with 'system' added. A system notice,
-- like a text message, has no file.
alter table public.messages drop constraint if exists messages_kind_check;
alter table public.messages add constraint messages_kind_check check (
    kind in ('text', 'image', 'voice', 'file', 'system')
    and (
        (kind in ('text', 'system') and media_path is null)
        or (kind not in ('text', 'system')
            and media_path is not null
            and media_path like conversation_id::text || '/%')
    )
    and (duration_ms is null or duration_ms between 0 and 600000)
    and (file_name is null or char_length(file_name) between 1 and 200)
    and (file_size is null or file_size between 0 and 5242880)
);

-- Only the server (the functions in these files) may write system notices.
-- This extra rule refuses any message of kind 'system' sent by the app.
drop policy if exists "messages: only the server writes notices" on public.messages;
create policy "messages: only the server writes notices"
    on public.messages
    as restrictive
    for insert
    to authenticated
    with check (kind <> 'system');

-- Same as in migration 13, plus: when the background really changes, a
-- notice is added to the conversation. The app shows it as
-- "<name> đã thay đổi hình nền" in the middle of the chat.
create or replace function public.set_wallpaper(p_conversation uuid, p_value text)
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

    if v_value is not null
       and v_value not in ('mint', 'peach', 'sky', 'lilac', 'sand')
       and v_value not like 'img:' || p_conversation::text || '/%' then
        raise exception 'invalid_wallpaper';
    end if;

    select wallpaper into v_old
    from public.conversations
    where id = p_conversation
      and v_me in (user_a, user_b);

    if not found then raise exception 'not_allowed'; end if;

    -- Nothing to do when the same background is chosen again.
    if v_old is not distinct from v_value then return; end if;

    update public.conversations set wallpaper = v_value where id = p_conversation;

    insert into public.messages (conversation_id, sender_id, content, kind)
    values (
        p_conversation,
        v_me,
        case when v_value is null then 'đã xóa hình nền' else 'đã thay đổi hình nền' end,
        'system'
    );
end;
$$;
