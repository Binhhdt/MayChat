-- =====================================================================
-- MayChat 0.37.1 — Hội thao characters bought with farm gold
-- Kai and Mika are free for everybody; Lina 5,000, Ryuki 10,000, Sora 15,000
-- gold, bought once and kept on the server.
-- Run once in Supabase → SQL Editor (after migration 36). Safe to run again.
-- =====================================================================

-- 1. The characters each farm owns
alter table public.farms add column if not exists heroes text[] not null default array['kai', 'mika'];

-- 2. The farm sent to the phone now also says which characters I own
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
        'heroes', to_jsonb(coalesce(f.heroes, array['kai', 'mika']))
    );
end;
$$;

-- 3. Buying a character
create or replace function public.farm_buy_hero(p_hero text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    f public.farms;
    v_price int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    v_price := case p_hero when 'lina' then 5000 when 'ryuki' then 10000 when 'sora' then 15000 else null end;
    if v_price is null then raise exception 'bad_hero'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    if p_hero = any(f.heroes) then raise exception 'owned'; end if;
    if f.gold < v_price then raise exception 'not_enough_gold'; end if;
    update public.farms
       set gold = gold - v_price, heroes = array_append(heroes, p_hero), updated_at = now()
     where user_id = v_me
     returning * into f;
    return public.farm_state(f);
end;
$$;

-- 4. Who may call it
revoke all on function public.farm_buy_hero(text) from public, anon;
grant execute on function public.farm_buy_hero(text) to authenticated;
