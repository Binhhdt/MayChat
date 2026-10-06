-- =====================================================================
-- MayChat - migration 15: "Đã nhận" (delivered) status
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one column and one function. Nothing existing is changed.
-- =====================================================================

-- When the receiver's app got the message (empty = not yet).
alter table public.messages add column if not exists delivered_at timestamptz;

-- Called by the receiver's app whenever it is running: marks every message
-- that was sent TO me and is not marked yet as delivered.
create or replace function public.mark_delivered()
returns void
language sql
security definer
set search_path = ''
as $$
    update public.messages m
    set delivered_at = now()
    from public.conversations c
    where c.id = m.conversation_id
      and auth.uid() in (c.user_a, c.user_b)
      and m.sender_id <> auth.uid()
      and m.delivered_at is null;
$$;

revoke execute on function public.mark_delivered() from public, anon;
grant execute on function public.mark_delivered() to authenticated;
