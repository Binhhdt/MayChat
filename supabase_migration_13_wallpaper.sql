-- =====================================================================
-- MayChat - migration 13: chat background shared by both people
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS one column and one function. Nothing existing is changed.
-- =====================================================================

-- The background of the conversation, seen by both members:
--   empty            = no background
--   mint, peach, ... = one of the ready-made color backgrounds
--   img:<path>       = a picture stored in this conversation's folder
alter table public.conversations add column if not exists wallpaper text;

-- Either member may change the background of their conversation.
create or replace function public.set_wallpaper(p_conversation uuid, p_value text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_value text := nullif(trim(coalesce(p_value, '')), '');
begin
    if v_me is null then raise exception 'not_authenticated'; end if;

    if v_value is not null
       and v_value not in ('mint', 'peach', 'sky', 'lilac', 'sand')
       and v_value not like 'img:' || p_conversation::text || '/%' then
        raise exception 'invalid_wallpaper';
    end if;

    update public.conversations
    set wallpaper = v_value
    where id = p_conversation
      and v_me in (user_a, user_b);

    if not found then raise exception 'not_allowed'; end if;
end;
$$;

revoke execute on function public.set_wallpaper(uuid, text) from public, anon;
grant execute on function public.set_wallpaper(uuid, text) to authenticated;
