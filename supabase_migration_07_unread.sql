-- =====================================================================
-- MayChat - migration 07: unread message count per conversation
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one function only. No existing table, rule or function is changed.
-- =====================================================================

-- For the logged-in user: how many messages from the other person are
-- still unread, per conversation. Conversations with 0 unread are left out.
create or replace function public.unread_counts()
returns table (conversation_id uuid, unread integer)
language sql
stable
security definer
set search_path = ''
as $$
    select m.conversation_id, count(*)::integer
    from public.messages m
    join public.conversations c on c.id = m.conversation_id
    where auth.uid() in (c.user_a, c.user_b)
      and m.sender_id <> auth.uid()
      and m.read_at is null
    group by m.conversation_id;
$$;

revoke execute on function public.unread_counts() from public, anon;
grant execute on function public.unread_counts() to authenticated;
