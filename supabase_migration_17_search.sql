-- =====================================================================
-- MayChat - migration 17: search that ignores Vietnamese accents
-- (typing "on" also finds "ổn", "ôn", "ơn")
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS two functions. Nothing existing is changed.
-- =====================================================================

-- Lower case, without Vietnamese accents: "Ổn định" -> "on dinh".
create or replace function public.vn_fold(p_text text)
returns text
language sql
immutable
set search_path = ''
as $$
    select translate(
        lower(normalize(coalesce(p_text, ''), NFC)),
        'àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ',
        'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd'
    );
$$;

-- Text messages of one of MY conversations that contain the words, accents
-- and upper/lower case ignored. Newest first, at most 300.
-- p_after: leave out messages up to this moment (used for a conversation I
-- deleted on my side); null = no limit.
create or replace function public.search_messages(
    p_conversation uuid,
    p_query text,
    p_after timestamptz
)
returns setof public.messages
language sql
stable
security definer
set search_path = ''
as $$
    select m.*
    from public.messages m
    join public.conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation
      and auth.uid() in (c.user_a, c.user_b)
      and m.kind = 'text'
      and m.recalled_at is null
      and (p_after is null or m.created_at > p_after)
      and char_length(trim(coalesce(p_query, ''))) >= 2
      and position(public.vn_fold(trim(p_query)) in public.vn_fold(m.content)) > 0
    order by m.created_at desc
    limit 300;
$$;

revoke execute on function public.search_messages(uuid, text, timestamptz) from public, anon;
grant execute on function public.search_messages(uuid, text, timestamptz) to authenticated;
