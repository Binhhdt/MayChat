-- =====================================================================
-- MayChat - migration 34: new prices of the farm (agreed)
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Needs migrations 31, 32 and 33 first.
--
-- CHANGES only numbers, nothing else:
--   - selling prices of the crops (less profit):
--       cà rốt 15, lúa mì 35, ngô 55, bắp cải 75, cà chua 95,
--       dâu tây 160, bí ngô 270 (seeds and growing times unchanged)
--   - houses: 5,000 / 15,000 / 40,000 / 100,000 gold
--   - Hội thao: 250 / 180 / 120, and 80 for 4th to 6th place
--     (the rest of the rules stay: 2 cups a day in full, then 20%,
--      bots "Dễ" 60%, with friends ×1.5)
-- Farms keep their gold and their house; crops already in the store are
-- sold at the new price.
-- =====================================================================

create or replace function public.farm_crop(p_id text)
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select ('{
        "carrot":     {"min": 5,   "seed": 10,  "sell": 15,  "lv": 1},
        "wheat":      {"min": 30,  "seed": 20,  "sell": 35,  "lv": 1},
        "corn":       {"min": 60,  "seed": 30,  "sell": 55, "lv": 1},
        "cabbage":    {"min": 90,  "seed": 40,  "sell": 75, "lv": 2},
        "tomato":     {"min": 120, "seed": 50,  "sell": 95, "lv": 2},
        "strawberry": {"min": 240, "seed": 80,  "sell": 160, "lv": 3},
        "pumpkin":    {"min": 480, "seed": 120, "sell": 270, "lv": 4}
    }'::jsonb) -> p_id
$$;

create or replace function public.farm_house_cost(lv int)
returns int
language sql
immutable
set search_path = ''
as $$
    select (array[0, 5000, 15000, 40000, 100000])[lv]
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

    n := case when f.cups_day = v_today then f.cups_n else 0 end;
    v_base := case p_place when 1 then 250 when 2 then 180 when 3 then 120 else 80 end;
    v_share := case when n < 2 then 1 when n < 10 then 0.2 else 0 end;
    -- bots "Dễ" pay 60%, bots "Vừa" pay in full
    v_gold := round(v_base * v_share
                    * case when p_level = 'easy' then 0.6 else 1 end
                    * case when coalesce(p_friends, 0) > 0 then 1.5 else 1 end)::int;

    update public.farms
       set gold = gold + v_gold, cups_day = v_today, cups_n = n + 1,
           last_cup_at = now(), updated_at = now()
     where user_id = v_me
    returning * into f;

    return public.farm_state(f) || jsonb_build_object(
        'reward', jsonb_build_object('gold', v_gold, 'base', v_base, 'share', v_share, 'n', n + 1, 'level', coalesce(p_level, 'mid'))
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
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into res from public.hoithao_results
     where room_id = p_room and user_id = v_me and not paid
     order by cup_no desc limit 1
     for update;
    if not found then raise exception 'no_result'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;

    n := case when f.cups_day = v_today then f.cups_n else 0 end;
    v_base := case res.place when 1 then 250 when 2 then 180 when 3 then 120 else 80 end;
    v_share := case when n < 2 then 1 when n < 10 then 0.2 else 0 end;
    v_gold := round(v_base * v_share
                    * case when res.level = 'easy' then 0.6 else 1 end
                    * case when res.humans > 1 then 1.5 else 1 end)::int;

    update public.hoithao_results set paid = true
     where room_id = res.room_id and cup_no = res.cup_no and user_id = v_me;
    update public.farms
       set gold = gold + v_gold, cups_day = v_today, cups_n = n + 1,
           last_cup_at = now(), updated_at = now()
     where user_id = v_me
    returning * into f;

    return public.farm_state(f) || jsonb_build_object(
        'reward', jsonb_build_object('gold', v_gold, 'base', v_base, 'share', v_share, 'n', n + 1,
                                     'level', res.level, 'friends', res.humans - 1, 'place', res.place)
    );
end;
$$;
