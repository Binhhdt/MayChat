-- =====================================================================
-- MayChat 0.38.0 — building materials for the houses
-- A house now needs gold AND materials:
--   Nhà gỗ   (2):   5,000 gold +  30 gỗ
--   Nhà ngói (3):  25,000 gold +  40 gỗ +  60 ngói
--   Nhà lầu  (4):  80,000 gold +  80 ngói + 100 gạch
--   Biệt thự (5): 200,000 gold + 100 gỗ + 150 ngói + 200 gạch
-- Materials come only from playing (not from the daily gift, not for gold):
--   gỗ:   harvesting, 1 plot in 5 gives 1 gỗ
--   ngói: Hội thao, the first 10 cups of a day: 1st place 3, 2nd 2, others 1
--   gạch: each daily task done 2; watering / taking at a friend's: 1 in 5
-- Run once in Supabase → SQL Editor (after migration 37). Safe to run again.
-- =====================================================================

-- 1. The materials of each farm
alter table public.farms add column if not exists mats jsonb not null default '{}'::jsonb;

-- 2. Helpers
create or replace function public.farm_house_mats(lv int)
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select (array[
        '{}'::jsonb,
        '{"go": 30}'::jsonb,
        '{"go": 40, "ngoi": 60}'::jsonb,
        '{"ngoi": 80, "gach": 100}'::jsonb,
        '{"go": 100, "ngoi": 150, "gach": 200}'::jsonb
    ])[lv]
$$;

create or replace function public.farm_mat_add(m jsonb, k text, n int)
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select coalesce(m, '{}'::jsonb) || jsonb_build_object(k, greatest(0, coalesce((m ->> k)::int, 0) + coalesce(n, 0)))
$$;

-- The 3 tasks of every day now also give 2 gạch each
create or replace function public.farm_quests()
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select '[{"id":1,"target":10,"gold":50,"gach":2},{"id":2,"target":300,"gold":50,"gach":2},{"id":3,"target":1,"gold":80,"gach":2}]'::jsonb
$$;

-- 3. The farm sent to the phone also has the materials
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
            'claimed', g.q_claimed, 'list', public.farm_quests()
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

-- 4. Building a house: gold and materials
create or replace function public.farm_upgrade()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    f public.farms;
    v_cost int;
    v_need jsonb;
    v_k text;
    v_n int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    if f.level >= 5 then raise exception 'max_level'; end if;
    v_cost := public.farm_house_cost(f.level + 1);
    if f.gold < v_cost then raise exception 'not_enough_gold'; end if;
    v_need := public.farm_house_mats(f.level + 1);
    for v_k, v_n in select key, value::int from jsonb_each_text(v_need) loop
        if coalesce((f.mats ->> v_k)::int, 0) < v_n then raise exception 'not_enough_mats'; end if;
        f.mats := public.farm_mat_add(f.mats, v_k, -v_n);
    end loop;

    update public.farms set gold = gold - v_cost, level = level + 1, mats = f.mats, updated_at = now()
     where user_id = v_me
    returning * into f;
    return public.farm_state(f);
end;
$$;

-- 5. Where materials come from
create or replace function public.farm_harvest(p_plots int[])
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_now bigint := public.farm_now_ms();
    f public.farms;
    p jsonb;
    v_crop text;
    v_parts int;
    k int;
    v_go int := 0;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if cardinality(p_plots) > 20 then raise exception 'too_many'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    f := public.farm_roll(f);

    foreach k in array coalesce(p_plots, '{}'::int[]) loop
        continue when k is null or k < 0 or k > 19 or public.farm_plot_level(k) > f.level;
        p := f.plots -> k;
        continue when jsonb_typeof(p) is distinct from 'object';
        continue when (p ->> 'at')::bigint + (p ->> 'dur')::bigint > v_now + 3000;
        v_crop := p ->> 'crop';
        v_parts := 5 - least(coalesce((p ->> 's')::int, 0), 4);   -- minus what friends took
        f.store := jsonb_set(f.store, array[v_crop], to_jsonb(coalesce((f.store ->> v_crop)::int, 0) + v_parts));
        f.plots := jsonb_set(f.plots, array[k::text], 'null'::jsonb);
        f.harvested := f.harvested + 1;
        f.q_harvest := f.q_harvest + 1;
        if random() < 0.2 then v_go := v_go + 1; end if;   -- 1 gỗ from 1 plot in 5
    end loop;

    f.mats := public.farm_mat_add(f.mats, 'go', v_go);
    update public.farms set plots = f.plots, store = f.store, harvested = f.harvested, mats = f.mats, updated_at = now()
     where user_id = v_me;
    perform public.farm_save_counters(f);
    return public.farm_state(f) || jsonb_build_object('drops', jsonb_build_object('go', v_go));
end;
$$;

create or replace function public.farm_cup(p_place int, p_players int, p_friends int, p_level text default 'mid')
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    n int;
    v_base int;
    v_share numeric;
    v_gold int;
    v_ngoi int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if coalesce(p_level, 'mid') not in ('easy', 'mid') then raise exception 'bad_level'; end if;
    if p_players is null or p_players < 2 or p_players > 6
       or p_place is null or p_place < 1 or p_place > p_players then
        raise exception 'bad_cup';
    end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    if f.last_cup_at is not null and f.last_cup_at > now() - interval '60 seconds' then
        raise exception 'cup_too_soon';
    end if;
    f := public.farm_roll(f);

    n := case when f.cups_day = v_today then f.cups_n else 0 end;
    v_base := case p_place when 1 then 250 when 2 then 180 when 3 then 120 else 80 end;
    v_share := case when n < 2 then 1 when n < 10 then 0.2 else 0 end;
    v_gold := round(v_base * v_share * case when p_level = 'easy' then 0.6 else 1 end)::int;
    -- ngói for the first 10 cups of a day: 1st place 3, 2nd 2, the others 1
    v_ngoi := case when n >= 10 then 0 when p_place = 1 then 3 when p_place = 2 then 2 else 1 end;

    f.week_gold := f.week_gold + v_gold;
    perform public.farm_save_counters(f);
    update public.farms
       set gold = gold + v_gold, mats = public.farm_mat_add(mats, 'ngoi', v_ngoi), cups_day = v_today, cups_n = n + 1,
           last_cup_at = now(), updated_at = now()
     where user_id = v_me
    returning * into f;

    return public.farm_state(f) || jsonb_build_object(
        'reward', jsonb_build_object('gold', v_gold, 'base', v_base, 'share', v_share, 'n', n + 1, 'level', coalesce(p_level, 'mid'), 'ngoi', v_ngoi)
    );
end;
$$;

create or replace function public.farm_cup_room(p_room uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    res public.hoithao_results;
    n int;
    v_base int;
    v_share numeric;
    v_gold int;
    v_ngoi int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into res from public.hoithao_results
     where room_id = p_room and user_id = v_me and not paid
     order by cup_no desc limit 1
     for update;
    if not found then raise exception 'no_result'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    f := public.farm_roll(f);

    n := case when f.cups_day = v_today then f.cups_n else 0 end;
    v_base := case res.place when 1 then 250 when 2 then 180 when 3 then 120 else 80 end;
    v_share := case when n < 2 then 1 when n < 10 then 0.2 else 0 end;
    v_gold := round(v_base * v_share * case when res.level = 'easy' then 0.6 else 1 end)::int;
    v_ngoi := case when n >= 10 then 0 when res.place = 1 then 3 when res.place = 2 then 2 else 1 end;

    update public.hoithao_results set paid = true
     where room_id = res.room_id and cup_no = res.cup_no and user_id = v_me;
    f.week_gold := f.week_gold + v_gold;
    perform public.farm_save_counters(f);
    update public.farms
       set gold = gold + v_gold, mats = public.farm_mat_add(mats, 'ngoi', v_ngoi), cups_day = v_today, cups_n = n + 1,
           last_cup_at = now(), updated_at = now()
     where user_id = v_me
    returning * into f;

    return public.farm_state(f) || jsonb_build_object(
        'reward', jsonb_build_object('gold', v_gold, 'base', v_base, 'share', v_share, 'n', n + 1,
                                     'level', res.level, 'friends', res.humans - 1, 'place', res.place, 'ngoi', v_ngoi)
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

create or replace function public.farm_water(p_friend uuid, p_plot int)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_now bigint := public.farm_now_ms();
    f public.farms;
    g public.farms;
    p jsonb;
    v_gach int := case when random() < 0.2 then 1 else 0 end;   -- 1 gạch in 5 helps
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_friend = v_me or not public.hoithao_are_friends(v_me, p_friend) then raise exception 'not_friend'; end if;
    if p_plot is null or p_plot < 0 or p_plot > 19 then raise exception 'bad_plot'; end if;
    -- both farms locked in the same order every time (no deadlock)
    perform 1 from public.farms where user_id in (v_me, p_friend) order by user_id for update;
    select * into g from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    g := public.farm_roll(g);
    if g.water_n >= 10 then raise exception 'no_water_left'; end if;
    select * into f from public.farms where user_id = p_friend for update;
    if not found then raise exception 'no_farm'; end if;
    p := f.plots -> p_plot;
    if jsonb_typeof(p) is distinct from 'object' then raise exception 'empty_plot'; end if;
    if (p ->> 'at')::bigint + (p ->> 'dur')::bigint <= v_now then raise exception 'already_ripe'; end if;
    if coalesce((p ->> 'w')::boolean, false) then raise exception 'watered'; end if;

    p := p || jsonb_build_object('at', (p ->> 'at')::bigint - round((p ->> 'dur')::bigint * 0.1)::bigint, 'w', true);
    update public.farms set plots = jsonb_set(plots, array[p_plot::text], p), updated_at = now() where user_id = p_friend;
    insert into public.farm_events (owner_id, actor_id, kind, crop) values (p_friend, v_me, 'water', p ->> 'crop');

    g.water_n := g.water_n + 1;
    g.week_gold := g.week_gold + 5;
    perform public.farm_save_counters(g);
    update public.farms set gold = gold + 5, mats = public.farm_mat_add(mats, 'gach', v_gach), updated_at = now() where user_id = v_me;
    return public.farm_visit(p_friend) || jsonb_build_object('gach', v_gach);
end;
$$;

create or replace function public.farm_steal(p_friend uuid, p_plot int)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_now bigint := public.farm_now_ms();
    f public.farms;
    g public.farms;
    p jsonb;
    v_gach int := case when random() < 0.2 then 1 else 0 end;   -- 1 gạch in 5 helps
    v_crop text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_friend = v_me or not public.hoithao_are_friends(v_me, p_friend) then raise exception 'not_friend'; end if;
    if p_plot is null or p_plot < 0 or p_plot > 19 then raise exception 'bad_plot'; end if;
    -- both farms locked in the same order every time (no deadlock)
    perform 1 from public.farms where user_id in (v_me, p_friend) order by user_id for update;
    select * into g from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    g := public.farm_roll(g);
    if g.steal_n >= 10 then raise exception 'no_steal_left'; end if;
    select * into f from public.farms where user_id = p_friend for update;
    if not found then raise exception 'no_farm'; end if;
    p := f.plots -> p_plot;
    if jsonb_typeof(p) is distinct from 'object' then raise exception 'empty_plot'; end if;
    if (p ->> 'at')::bigint + (p ->> 'dur')::bigint > v_now + 3000 then raise exception 'not_ripe'; end if;
    if coalesce((p ->> 's')::int, 0) >= 1 then raise exception 'taken'; end if;

    v_crop := p ->> 'crop';
    p := p || jsonb_build_object('s', 1);
    update public.farms set plots = jsonb_set(plots, array[p_plot::text], p), updated_at = now() where user_id = p_friend;
    insert into public.farm_events (owner_id, actor_id, kind, crop) values (p_friend, v_me, 'steal', v_crop);

    g.steal_n := g.steal_n + 1;
    perform public.farm_save_counters(g);
    update public.farms
       set store = jsonb_set(store, array[v_crop], to_jsonb(coalesce((store ->> v_crop)::int, 0) + 1)),
           mats = public.farm_mat_add(mats, 'gach', v_gach), updated_at = now()
     where user_id = v_me;
    return public.farm_visit(p_friend) || jsonb_build_object('gach', v_gach);
end;
$$;

-- 6. Who may call them (the helpers only from inside)
revoke all on function public.farm_house_mats(int)          from public, anon, authenticated;
revoke all on function public.farm_mat_add(jsonb, text, int) from public, anon, authenticated;
