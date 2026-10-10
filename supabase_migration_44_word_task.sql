-- =====================================================================
-- MayChat 0.40.2 — Đuổi hình bắt chữ
--  * The letter tiles are exactly the letters of the answer (none more,
--    none missing).
--  * A 4th task of the day: play Đuổi hình bắt chữ (finish at least one
--    puzzle) — 20 gold.
-- Run once in Supabase → SQL Editor (after migration 43). Safe to run again.
-- =====================================================================

-- the 4 tasks of every day
create or replace function public.farm_quests()
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select '[{"id":1,"target":10,"gold":50,"gach":2},{"id":2,"target":300,"gold":50,"gach":2},{"id":3,"target":1,"gold":80,"gach":2},{"id":4,"target":1,"gold":20}]'::jsonb
$$;

create or replace function public.farm_state(f public.farms)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
    v_today date := public.farm_today();
    g public.farms := public.farm_roll(f);
    v_next int;
begin
    -- the day of the login row a claim today would be
    v_next := case when f.login_day = v_today then f.login_streak
                   when f.login_day = v_today - 1 then (f.login_streak % 7) + 1
                   else 1 end;
    return jsonb_build_object(
        'name', f.name,
        'farmer', f.farmer,
        'gold', f.gold,
        'level', f.level,
        'plots', f.plots,
        'store', f.store,
        'harvested', f.harvested,
        'cups_today', case when f.cups_day = v_today then f.cups_n else 0 end,
        'server_now', public.farm_now_ms(),
        'parts', 5,
        'daily', jsonb_build_object(
            'claimed', coalesce(f.login_day = v_today, false),
            'day', v_next,
            'gifts', jsonb_build_array(30, 50, 70, 90, 110, 140, 200)
        ),
        'quests', jsonb_build_object(
            'harvest', g.q_harvest, 'sold', g.q_sold,
            'cups', case when f.cups_day = v_today then f.cups_n else 0 end,
            'claimed', g.q_claimed, 'list', public.farm_quests(),
            'word', case when (f.word_day = v_today and f.word_n > 0) or f.word_lost = v_today then 1 else 0 end
        ),
        'week_gold', g.week_gold,
        'water_left', 10 - g.water_n,
        'steal_left', 10 - g.steal_n,
        'heroes', to_jsonb(coalesce(f.heroes, array['kai', 'mika'])),
        'mats', jsonb_build_object('go', coalesce((f.mats ->> 'go')::int, 0),
                                   'ngoi', coalesce((f.mats ->> 'ngoi')::int, 0),
                                   'gach', coalesce((f.mats ->> 'gach')::int, 0))
    );
end;
$$;

create or replace function public.farm_quest_claim(p_quest int)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    q jsonb;
    v_have int;
    v_bit int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    f := public.farm_roll(f);
    select x into q from jsonb_array_elements(public.farm_quests()) x where (x ->> 'id')::int = p_quest;
    if q is null then raise exception 'bad_quest'; end if;
    v_bit := 1 << (p_quest - 1);
    if f.q_claimed & v_bit <> 0 then raise exception 'claimed'; end if;
    v_have := case p_quest when 1 then f.q_harvest when 2 then f.q_sold
              when 4 then (case when (f.word_day = v_today and f.word_n > 0) or f.word_lost = v_today then 1 else 0 end)
              else (case when f.cups_day = v_today then f.cups_n else 0 end) end;
    if v_have < (q ->> 'target')::int then raise exception 'not_done'; end if;
    f.q_claimed := f.q_claimed | v_bit;
    f.week_gold := f.week_gold + (q ->> 'gold')::int;
    perform public.farm_save_counters(f);
    update public.farms set gold = gold + (q ->> 'gold')::int,
           mats = public.farm_mat_add(mats, 'gach', coalesce((q ->> 'gach')::int, 0)), updated_at = now() where user_id = v_me returning * into f;
    return public.farm_state(f) || jsonb_build_object('quest_gold', (q ->> 'gold')::int, 'quest_gach', coalesce((q ->> 'gach')::int, 0));
end;
$$;

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
    select * into w from public.farm_words where active and id = f.word_cur;
    if not found then
        -- no puzzle chosen yet (the next one is drawn when the player asks for it)
        return jsonb_build_object('n', v_n, 'max', 10,
            'finished', not exists (select 1 from public.farm_words where active), 'waiting', true,
            'over', f.word_lost is not distinct from v_today, 'skip_left', case when f.word_skip_day is not distinct from v_today then 0 else 1 end);
    end if;
    v_ans := replace(w.answer, ' ', '');
    v_len := char_length(v_ans);
    -- the letters of the answer, then some extra letters (always the same for a puzzle)
    for i in 1 .. v_len loop v_tiles := v_tiles || substr(v_ans, i, 1); end loop;
    v_extra := 0;   -- exactly the letters of the answer (no extra letters)
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
        'id', w.id, 'no', v_n + 1, 'level', w.level, 'pics', w.pics, 'img', w.img,
        'words', (select jsonb_agg(char_length(x) order by k) from unnest(string_to_array(w.answer, ' ')) with ordinality as s(x, k)),
        'letters', v_letters, 'open', v_open, 'cut', f.word_cut,
        'costs', jsonb_build_object('letter', 20, 'cut', 30, 'skip', 50),
        'limit', 60,
        'over', f.word_lost is not distinct from v_today,
        'skip_left', case when f.word_skip_day is not distinct from v_today then 0 else 1 end,
        'left_ms', case when f.word_at is null then 60000
                        else greatest(0, 60000 - floor(extract(epoch from (now() - f.word_at)) * 1000)::int) end
    );
end;
$$;
