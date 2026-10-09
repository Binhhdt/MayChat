-- =====================================================================
-- MayChat - migration 31: the farm game (tab "Game")
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS only: one new table (public.farms) and new functions whose names
-- start with "farm_". No existing table, function or rule is changed.
--
-- The server is the judge: gold, crops, the time a crop is ripe, house
-- upgrades and the Hội thao reward are all decided here, with the
-- server's clock. The phone only shows the result, so changing the time
-- of the phone or editing the game page does not give free gold.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. ONE FARM PER ACCOUNT
-- ---------------------------------------------------------------------

create table if not exists public.farms (
    user_id      uuid primary key references public.profiles (id) on delete cascade,
    name         text not null check (char_length(name) between 2 and 20),
    farmer       text not null default 'boy' check (farmer in ('boy', 'girl')),
    gold         bigint not null default 300 check (gold >= 0),
    level        int not null default 1 check (level between 1 and 5),
    -- 20 plots, each null (empty) or {"crop": "...", "at": ms, "dur": ms}
    plots        jsonb not null,
    -- harvested crops waiting to be sold: {"carrot": 3, ...}
    store        jsonb not null default '{}'::jsonb,
    harvested    int not null default 0,
    -- Hội thao cups played today (Vietnam date) and the last one
    cups_day     date,
    cups_n       int not null default 0,
    last_cup_at  timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

-- Nobody reads or writes the table directly; only the functions below do.
alter table public.farms enable row level security;
revoke all on public.farms from anon, authenticated;


-- ---------------------------------------------------------------------
-- 2. THE RULES (same numbers as the game page)
-- ---------------------------------------------------------------------

-- min: minutes to grow · seed: cost · sell: price · lv: house level needed
create or replace function public.farm_crop(p_id text)
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select ('{
        "carrot":     {"min": 5,   "seed": 10,  "sell": 25,  "lv": 1},
        "wheat":      {"min": 30,  "seed": 20,  "sell": 60,  "lv": 1},
        "corn":       {"min": 60,  "seed": 30,  "sell": 100, "lv": 1},
        "cabbage":    {"min": 90,  "seed": 40,  "sell": 135, "lv": 2},
        "tomato":     {"min": 120, "seed": 50,  "sell": 170, "lv": 2},
        "strawberry": {"min": 240, "seed": 80,  "sell": 300, "lv": 3},
        "pumpkin":    {"min": 480, "seed": 120, "sell": 520, "lv": 4}
    }'::jsonb) -> p_id
$$;

-- The house level that opens plot k (0..19): 6, 9, 12, 16, 20 plots.
create or replace function public.farm_plot_level(k int)
returns int
language sql
immutable
set search_path = ''
as $$
    select case when k < 6 then 1 when k < 9 then 2 when k < 12 then 3 when k < 16 then 4 else 5 end
$$;

-- Price of the house of level lv (1..5).
create or replace function public.farm_house_cost(lv int)
returns int
language sql
immutable
set search_path = ''
as $$
    select (array[0, 1000, 3000, 8000, 25000])[lv]
$$;

create or replace function public.farm_now_ms()
returns bigint
language sql
stable
set search_path = ''
as $$
    select (extract(epoch from now()) * 1000)::bigint
$$;

create or replace function public.farm_today()
returns date
language sql
stable
set search_path = ''
as $$
    select (now() at time zone 'Asia/Ho_Chi_Minh')::date
$$;

-- "  Hoa   hướng dương " -> "Hoa hướng dương"; 2 to 20 characters.
create or replace function public.farm_clean_name(p_name text)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare
    v text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
begin
    if char_length(v) < 2 or char_length(v) > 20 then raise exception 'bad_name'; end if;
    return v;
end;
$$;

-- What the phone gets back after every action.
create or replace function public.farm_state(f public.farms)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'name', f.name,
        'farmer', f.farmer,
        'gold', f.gold,
        'level', f.level,
        'plots', f.plots,
        'store', f.store,
        'harvested', f.harvested,
        'cups_today', case when f.cups_day = public.farm_today() then f.cups_n else 0 end,
        'server_now', public.farm_now_ms()
    )
$$;


-- ---------------------------------------------------------------------
-- 3. ACTIONS (each returns the whole farm)
-- ---------------------------------------------------------------------

-- My farm, or null when I have not made one yet.
create or replace function public.farm_get()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    f public.farms;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me;
    if not found then return null; end if;
    return public.farm_state(f);
end;
$$;

-- First visit: name the farm and pick the farmer. Starts with 300 gold
-- and two plots of carrots that are ripe within a minute.
create or replace function public.farm_create(p_name text, p_farmer text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_now bigint := public.farm_now_ms();
    v_plots jsonb;
    f public.farms;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_farmer not in ('boy', 'girl') then raise exception 'bad_farmer'; end if;
    select * into f from public.farms where user_id = v_me;
    if found then return public.farm_state(f); end if;   -- already made (pressed twice)

    select jsonb_agg(null::jsonb) into v_plots from generate_series(1, 20);
    v_plots := jsonb_set(v_plots, '{0}', jsonb_build_object('crop', 'carrot', 'at', v_now - 270000, 'dur', 300000));
    v_plots := jsonb_set(v_plots, '{1}', jsonb_build_object('crop', 'carrot', 'at', v_now - 240000, 'dur', 300000));

    insert into public.farms (user_id, name, farmer, plots)
    values (v_me, public.farm_clean_name(p_name), p_farmer, v_plots)
    on conflict (user_id) do nothing;

    select * into f from public.farms where user_id = v_me;
    return public.farm_state(f);
end;
$$;

-- Change the name and/or the farmer.
create or replace function public.farm_rename(p_name text, p_farmer text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    f public.farms;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_farmer not in ('boy', 'girl') then raise exception 'bad_farmer'; end if;
    update public.farms
       set name = public.farm_clean_name(p_name), farmer = p_farmer, updated_at = now()
     where user_id = v_me
    returning * into f;
    if not found then raise exception 'no_farm'; end if;
    return public.farm_state(f);
end;
$$;

-- Sow one crop on the given plots (0..19). Plots that are locked or not
-- empty are skipped; it stops when the gold runs out.
create or replace function public.farm_plant(p_plots int[], p_crop text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_now bigint := public.farm_now_ms();
    f public.farms;
    c jsonb := public.farm_crop(p_crop);
    v_seed int;
    v_dur bigint;
    k int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if c is null then raise exception 'bad_crop'; end if;
    if cardinality(p_plots) > 20 then raise exception 'too_many'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    if f.level < (c ->> 'lv')::int then raise exception 'crop_locked'; end if;

    v_seed := (c ->> 'seed')::int;
    -- from house level 3 crops grow 10% faster
    v_dur := round((c ->> 'min')::numeric * 60000 * case when f.level >= 3 then 0.9 else 1 end)::bigint;
    foreach k in array coalesce(p_plots, '{}'::int[]) loop
        exit when f.gold < v_seed;
        continue when k is null or k < 0 or k > 19 or public.farm_plot_level(k) > f.level;
        continue when jsonb_typeof(f.plots -> k) = 'object';
        f.gold := f.gold - v_seed;
        f.plots := jsonb_set(f.plots, array[k::text], jsonb_build_object('crop', p_crop, 'at', v_now, 'dur', v_dur));
    end loop;

    update public.farms set gold = f.gold, plots = f.plots, updated_at = now() where user_id = v_me;
    return public.farm_state(f);
end;
$$;

-- Harvest the given plots: only crops that are ripe by the server's clock
-- (3 seconds of grace for the phone's display) go into the store.
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
    k int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if cardinality(p_plots) > 20 then raise exception 'too_many'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;

    foreach k in array coalesce(p_plots, '{}'::int[]) loop
        continue when k is null or k < 0 or k > 19 or public.farm_plot_level(k) > f.level;
        p := f.plots -> k;
        continue when jsonb_typeof(p) is distinct from 'object';
        continue when (p ->> 'at')::bigint + (p ->> 'dur')::bigint > v_now + 3000;
        v_crop := p ->> 'crop';
        f.store := jsonb_set(f.store, array[v_crop], to_jsonb(coalesce((f.store ->> v_crop)::int, 0) + 1));
        f.plots := jsonb_set(f.plots, array[k::text], 'null'::jsonb);
        f.harvested := f.harvested + 1;
    end loop;

    update public.farms set plots = f.plots, store = f.store, harvested = f.harvested, updated_at = now()
     where user_id = v_me;
    return public.farm_state(f);
end;
$$;

-- Sell p_n of one crop, or everything in the store when p_crop is null.
create or replace function public.farm_sell(p_crop text, p_n int)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    f public.farms;
    c jsonb;
    v_id text;
    v_have int;
    m int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;

    for v_id, v_have in select key, value::int from jsonb_each_text(f.store) loop
        continue when p_crop is not null and v_id <> p_crop;
        c := public.farm_crop(v_id);
        continue when c is null;
        m := case when p_crop is null then v_have else least(v_have, greatest(coalesce(p_n, 0), 0)) end;
        continue when m <= 0;
        f.gold := f.gold + m * (c ->> 'sell')::int;
        f.store := jsonb_set(f.store, array[v_id], to_jsonb(v_have - m));
    end loop;

    update public.farms set gold = f.gold, store = f.store, updated_at = now() where user_id = v_me;
    return public.farm_state(f);
end;
$$;

-- Build the next house (1,000 / 3,000 / 8,000 / 25,000 gold).
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
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    if f.level >= 5 then raise exception 'max_level'; end if;
    v_cost := public.farm_house_cost(f.level + 1);
    if f.gold < v_cost then raise exception 'not_enough_gold'; end if;

    update public.farms set gold = gold - v_cost, level = level + 1, updated_at = now()
     where user_id = v_me
    returning * into f;
    return public.farm_state(f);
end;
$$;

-- A Hội thao cup has ended. Reward by final place: 500 / 350 / 250, 150
-- for 4th to 6th. The first 2 cups of a day (Vietnam date) pay in full,
-- the next ones 20%; with friends ×1.5. At most 10 paid cups a day and
-- at least 60 seconds between two cups.
create or replace function public.farm_cup(p_place int, p_players int, p_friends int)
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
    v_base := case p_place when 1 then 500 when 2 then 350 when 3 then 250 else 150 end;
    v_share := case when n < 2 then 1 when n < 10 then 0.2 else 0 end;
    v_gold := round(v_base * v_share * case when coalesce(p_friends, 0) > 0 then 1.5 else 1 end)::int;

    update public.farms
       set gold = gold + v_gold, cups_day = v_today, cups_n = n + 1,
           last_cup_at = now(), updated_at = now()
     where user_id = v_me
    returning * into f;

    return public.farm_state(f) || jsonb_build_object(
        'reward', jsonb_build_object('gold', v_gold, 'base', v_base, 'share', v_share, 'n', n + 1)
    );
end;
$$;


-- ---------------------------------------------------------------------
-- 4. WHO MAY CALL THEM: logged-in users only
-- ---------------------------------------------------------------------

revoke all on function public.farm_get()                    from public, anon;
revoke all on function public.farm_create(text, text)       from public, anon;
revoke all on function public.farm_rename(text, text)       from public, anon;
revoke all on function public.farm_plant(int[], text)       from public, anon;
revoke all on function public.farm_harvest(int[])           from public, anon;
revoke all on function public.farm_sell(text, int)          from public, anon;
revoke all on function public.farm_upgrade()                from public, anon;
revoke all on function public.farm_cup(int, int, int)       from public, anon;
revoke all on function public.farm_state(public.farms)      from public, anon, authenticated;

grant execute on function public.farm_get()                 to authenticated;
grant execute on function public.farm_create(text, text)    to authenticated;
grant execute on function public.farm_rename(text, text)    to authenticated;
grant execute on function public.farm_plant(int[], text)    to authenticated;
grant execute on function public.farm_harvest(int[])        to authenticated;
grant execute on function public.farm_sell(text, int)       to authenticated;
grant execute on function public.farm_upgrade()             to authenticated;
grant execute on function public.farm_cup(int, int, int)    to authenticated;
