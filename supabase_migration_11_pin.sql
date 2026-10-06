-- =====================================================================
-- MayChat - migration 11: pin one message at the top of a conversation
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one column and three functions. Nothing existing is changed.
-- =====================================================================

-- The pinned message of the conversation (empty = nothing pinned).
-- If that message is ever deleted, the pin simply disappears.
alter table public.conversations
    add column if not exists pinned_message_id uuid references public.messages (id) on delete set null;

-- Pins a message. Both members of the conversation may pin; a new pin
-- replaces the old one.
create or replace function public.pin_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_conv uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    select m.conversation_id into v_conv
    from public.messages m
    join public.conversations c on c.id = m.conversation_id
    where m.id = p_message
      and v_me in (c.user_a, c.user_b)
      and m.recalled_at is null;

    if v_conv is null then raise exception 'not_allowed'; end if;

    update public.conversations set pinned_message_id = p_message where id = v_conv;
end;
$$;

create or replace function public.unpin_message(p_conversation uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.conversations
    set pinned_message_id = null
    where id = p_conversation
      and auth.uid() in (user_a, user_b);
end;
$$;

-- The pinned message of a conversation I am a member of (no row = no pin).
-- The text is read fresh each time, so a recalled message shows as recalled.
create or replace function public.pinned_message(p_conversation uuid)
returns table (message_id uuid, content text, created_at timestamptz, kind text)
language sql
stable
security definer
set search_path = ''
as $$
    select m.id, m.content, m.created_at, m.kind
    from public.conversations c
    join public.messages m on m.id = c.pinned_message_id
    where c.id = p_conversation
      and auth.uid() in (c.user_a, c.user_b);
$$;

revoke execute on function public.pin_message(uuid)       from public, anon;
revoke execute on function public.unpin_message(uuid)     from public, anon;
revoke execute on function public.pinned_message(uuid)    from public, anon;
grant execute on function public.pin_message(uuid)        to authenticated;
grant execute on function public.unpin_message(uuid)      to authenticated;
grant execute on function public.pinned_message(uuid)     to authenticated;
