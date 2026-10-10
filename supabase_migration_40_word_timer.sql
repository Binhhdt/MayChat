-- =====================================================================
-- MayChat 0.39.1 — "Nhìn hình đoán chữ": 1 minute for each puzzle
-- The minute starts when the puzzle is shown (counted by the server, so
-- closing and opening the game again gives no extra time). When it runs
-- out, the puzzle is lost: it counts in the 10 of the day, no gold.
-- Run once in Supabase → SQL Editor (after migration 39). Safe to run again.
-- =====================================================================

alter table public.farms add column if not exists word_at timestamptz;   -- when the shown puzzle started

create or replace function public.farm_word_view(f public.farms)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_today date := public.farm_today();
    v_n int := case when f.word_day = v_today then f.word_n else 0 end;
    w public.farm_words;
    v_ans text;
    v_len int;
    v_tiles text[] := '{}';
    v_abc text := 'ABCDEGHIKLMNOPQRSTUVXY';
    v_extra int;
    v_letters jsonb;
    v_open jsonb;
    i int;
begin
    select * into w from public.farm_words where active and id > f.word_last order by id limit 1;
    if not found then
        return jsonb_build_object('n', v_n, 'max', 10, 'finished', true);
    end if;
    v_ans := replace(w.answer, ' ', '');
    v_len := char_length(v_ans);
    -- the letters of the answer, then some extra letters (always the same for a puzzle)
    for i in 1 .. v_len loop v_tiles := v_tiles || substr(v_ans, i, 1); end loop;
    v_extra := greatest(4, 14 - v_len);
    for i in 1 .. v_extra loop
        v_tiles := v_tiles || substr(v_abc, 1 + ('x' || substr(md5(w.id || ':e:' || i), 1, 6))::bit(24)::int % char_length(v_abc), 1);
    end loop;
    select jsonb_agg(t.l order by md5(w.id || ':s:' || t.k)) into v_letters
      from unnest(v_tiles) with ordinality as t(l, k)
     where not f.word_cut or t.k <= v_len;
    select coalesce(jsonb_agg(jsonb_build_object('i', o, 'c', substr(v_ans, o + 1, 1)) order by o), '[]'::jsonb) into v_open
      from unnest(f.word_open) as o;
    return jsonb_build_object(
        'n', v_n, 'max', 10, 'finished', false,
        'id', w.id, 'level', w.level, 'pics', w.pics,
        'words', (select jsonb_agg(char_length(x) order by k) from unnest(string_to_array(w.answer, ' ')) with ordinality as s(x, k)),
        'letters', v_letters, 'open', v_open, 'cut', f.word_cut,
        'costs', jsonb_build_object('letter', 20, 'cut', 30, 'skip', 50),
        'limit', 60,
        'left_ms', case when f.word_at is null then 60000
                        else greatest(0, 60000 - floor(extract(epoch from (now() - f.word_at)) * 1000)::int) end
    );
end;
$$;

create or replace function public.farm_word_get()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    w public.farm_words;
    v_n int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    v_n := case when f.word_day = v_today then f.word_n else 0 end;
    select * into w from public.farm_words where active and id > f.word_last order by id limit 1;
    if found and v_n < 10 then
        if f.word_at is not null and now() >= f.word_at + interval '60 seconds' then
            -- time ran out (the game was closed, or the minute passed): this puzzle is lost
            update public.farms
               set word_last = w.id, word_day = v_today, word_n = v_n + 1, word_open = '{}', word_cut = false,
                   word_at = null, updated_at = now()
             where user_id = v_me returning * into f;
            return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f),
                'result', jsonb_build_object('timeout', true, 'shown', w.shown));
        end if;
        if f.word_at is null then
            -- the minute of this puzzle starts now
            update public.farms set word_at = now() where user_id = v_me returning * into f;
        end if;
    end if;
    return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f));
end;
$$;

create or replace function public.farm_word_answer(p_letters text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    w public.farm_words;
    v_n int;
    v_mat text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    v_n := case when f.word_day = v_today then f.word_n else 0 end;
    if v_n >= 10 then raise exception 'word_day_done'; end if;
    select * into w from public.farm_words where active and id > f.word_last order by id limit 1;
    if not found then raise exception 'word_finished'; end if;
    if f.word_at is null then raise exception 'word_not_started'; end if;
    if now() > f.word_at + interval '62 seconds' then
        -- too late (2 seconds kept for a slow network): this puzzle is lost
        update public.farms
           set word_last = w.id, word_day = v_today, word_n = v_n + 1, word_open = '{}', word_cut = false,
               word_at = null, updated_at = now()
         where user_id = v_me returning * into f;
        return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f),
            'result', jsonb_build_object('ok', false, 'timeout', true, 'shown', w.shown));
    end if;

    if regexp_replace(upper(coalesce(p_letters, '')), '[^A-Z]', '', 'g') <> replace(w.answer, ' ', '') then
        return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f), 'result', jsonb_build_object('ok', false));
    end if;

    -- right: 10 gold, and 1 time in 10 a material
    if random() < 0.1 then v_mat := (array['go', 'ngoi', 'gach'])[1 + floor(random() * 3)::int]; end if;
    f := public.farm_roll(f);
    f.week_gold := f.week_gold + 10;
    perform public.farm_save_counters(f);
    update public.farms
       set gold = gold + 10,
           mats = case when v_mat is null then mats else public.farm_mat_add(mats, v_mat, 1) end,
           word_last = w.id, word_day = v_today, word_n = v_n + 1, word_open = '{}', word_cut = false,
           word_at = null, updated_at = now()
     where user_id = v_me
    returning * into f;
    return public.farm_state(f) || jsonb_build_object(
        'word', public.farm_word_view(f),
        'result', jsonb_build_object('ok', true, 'shown', w.shown, 'gold', 10, 'mat', v_mat)
    );
end;
$$;

create or replace function public.farm_word_hint(p_kind text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    w public.farm_words;
    v_n int;
    v_cost int;
    v_len int;
    v_next int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    v_cost := case p_kind when 'letter' then 20 when 'cut' then 30 when 'skip' then 50 else null end;
    if v_cost is null then raise exception 'bad_hint'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    v_n := case when f.word_day = v_today then f.word_n else 0 end;
    if v_n >= 10 then raise exception 'word_day_done'; end if;
    select * into w from public.farm_words where active and id > f.word_last order by id limit 1;
    if not found then raise exception 'word_finished'; end if;
    if f.word_at is null or now() > f.word_at + interval '62 seconds' then raise exception 'word_timeout'; end if;
    if f.gold < v_cost then raise exception 'not_enough_gold'; end if;
    v_len := char_length(replace(w.answer, ' ', ''));

    if p_kind = 'letter' then
        select min(i) into v_next from generate_series(0, v_len - 1) i where not (i = any(f.word_open));
        if v_next is null or cardinality(f.word_open) >= v_len - 1 then raise exception 'no_more_hint'; end if;
        update public.farms set gold = gold - v_cost, word_open = word_open || v_next, updated_at = now()
         where user_id = v_me returning * into f;
    elsif p_kind = 'cut' then
        if f.word_cut then raise exception 'no_more_hint'; end if;
        update public.farms set gold = gold - v_cost, word_cut = true, updated_at = now()
         where user_id = v_me returning * into f;
    else
        update public.farms
           set gold = gold - v_cost, word_last = w.id, word_day = v_today, word_n = v_n + 1,
               word_open = '{}', word_cut = false, word_at = null, updated_at = now()
         where user_id = v_me returning * into f;
        return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f),
            'result', jsonb_build_object('skipped', true, 'shown', w.shown));
    end if;
    return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f));
end;
$$;
