-- =====================================================================
-- MayChat - migration 12: send any file (at most 5 MB)
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS two columns. CHANGES two existing things, both only to allow the
-- new kind of message:
--   1. the rule "which kinds of message exist" now also accepts 'file';
--   2. recall_message also clears the file name of a recalled file.
-- The 5 MB limit of the storage bucket is NOT changed.
-- =====================================================================

-- Original name and size of a sent file (empty for other messages).
alter table public.messages add column if not exists file_name text;
alter table public.messages add column if not exists file_size integer;

-- Same rule as before, with 'file' added to the list of kinds.
alter table public.messages drop constraint if exists messages_kind_check;
alter table public.messages add constraint messages_kind_check check (
    kind in ('text', 'image', 'voice', 'file')
    and (
        (kind = 'text' and media_path is null)
        or (kind <> 'text'
            and media_path is not null
            and media_path like conversation_id::text || '/%')
    )
    and (duration_ms is null or duration_ms between 0 and 600000)
    and (file_name is null or char_length(file_name) between 1 and 200)
    and (file_size is null or file_size between 0 and 5242880)
);

-- The app may fill in the two new columns when sending.
grant insert (file_name, file_size) on public.messages to authenticated;

-- Recall: identical to migration 10, plus clearing the file name and size.
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
        file_name = null,
        file_size = null,
        recalled_at = now()
    where id = p_message;

    update public.conversations
    set last_message_text = 'Tin nhắn đã được thu hồi'
    where id = v_msg.conversation_id
      and last_sender_id = v_msg.sender_id
      and last_message_at = v_msg.created_at;

    update public.messages
    set reply_preview = 'Tin nhắn đã được thu hồi'
    where reply_to_id = p_message;

    delete from public.message_reactions where message_id = p_message;
end;
$$;
