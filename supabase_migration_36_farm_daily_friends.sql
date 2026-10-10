-- =====================================================================
-- MayChat - migration 36: farm, stages 3 and 4 (agreed)
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Needs migrations 31 to 35 first.
--
-- STAGE 3 (daily, decided by the server):
--   - a gift for logging in, 7 days in a row (missing a day starts again)
--   - 3 tasks a day with a reward each
--   - a weekly ranking of friends by the gold earned this week
-- STAGE 4 (friends):
--   - visiting a friend's farm; watering a growing plot (ripe 10% of its
--     time sooner), helping gives the helper 5 gold
--   - taking ("hái trộm") 1 of the 5 parts of a ripe plot of a friend
--   - the farm (name and house) on the MayChat profile page
--
-- CHANGES (agreed): a harvested plot now gives 5 parts instead of 1, each
-- sold at a fifth of the price (same gold for a whole plot). What is in a
-- store already is multiplied by 5 once, so nobody loses anything.
-- farm_state, farm_harvest, farm_sell, farm_cup and farm_cup_room are
-- replaced to count the daily tasks and the gold of the week.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. NEW COLUMNS AND TABLES
-- ---------------------------------------------------------------------

alter table public.farms add column if not exists login_day    date;
alter table public.farms add column if not exists login_streak int not null default 0;
alter table public.farms add column if not exists quest_day    date;
alter table public.farms add column if not exists q_harvest    int not null default 0;
alter table public.farms add column if not exists q_sold       int not null default 0;
alter table public.farms add column if not exists q_claimed    int not null default 0;
alter table public.farms add column if not exists week_start   date;
alter table public.farms add column if not exists week_gold    bigint not null default 0;
alter table public.farms add column if not exists help_day     date;
alter table public.farms add column if not exists water_n      int not null default 0;
alter table public.farms add column if not exists steal_n      int not null default 0;

-- 5 parts per plot: the store of the farms made before is multiplied once
alter table public.farms add column if not exists units5 boolean;
update public.farms
   set store = coalesce((select jsonb_object_agg(key, (value::int) * 5) from jsonb_each_text(store)), '{}'::jsonb),
       units5 = true
 where units5 is null;
alter table public.farms alter column units5 set default true;

-- What friends did on my farm (for "Hoạt động").
create table if not exists public.farm_events (
    id          bigint generated always as identity primary key,
    owner_id    uuid not null references public.profiles (id) on delete cascade,
    actor_id    uuid not null references public.profiles (id) on delete cascade,
    kind        text not null check (kind in ('water', 'steal')),
    crop        text not null,
    created_at  timestamptz not null default now()
);
create index if not exists farm_events_owner_idx on public.farm_events (owner_id, created_at desc);
alter table public.farm_events enable row level security;
revoke all on public.farm_events from anon, authenticated;


-- ---------------------------------------------------------------------
-- 2. RULES
-- ---------------------------------------------------------------------

-- Monday of this week (Vietnam date).
create or replace function public.farm_week()
returns date
language sql
stable
set search_path = ''
as $$
    select (date_trunc('week', (now() at time zone 'Asia/Ho_Chi_Minh')))::date
$$;

-- Gift of day 1..7 of the login row.
create or replace function public.farm_login_gift(d int)
returns int
language sql
immutable
set search_path = ''
as $$
    select (array[30, 50, 70, 90, 110, 140, 200])[greatest(1, least(7, d))]
$$;

-- The 3 tasks of every day: [what, target, gold]
--   1: harvest 10 plots · 2: sell for 300 gold · 3: play 1 Hội thao cup
create or replace function public.farm_quests()
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select '[{"id":1,"target":10,"gold":50},{"id":2,"target":300,"gold":50},{"id":3,"target":1,"gold":80}]'::jsonb
$$;

-- Counters of today / this week, reset when the day / week changed.
create or replace function public.farm_roll(f public.farms)
returns public.farms
language plpgsql
stable
set search_path = ''
as $$
declare
    v_today date := public.farm_today();
begin
    if f.quest_day is distinct from v_today then
        f.quest_day := v_today; f.q_harvest := 0; f.q_sold := 0; f.q_claimed := 0;
    end if;
    if f.help_day is distinct from v_today then
        f.help_day := v_today; f.water_n := 0; f.steal_n := 0;
    end if;
    if f.week_start is distinct from public.farm_week() then
        f.week_start := public.farm_week(); f.week_gold := 0;
    end if;
    return f;
end;
$$;

-- Saves the counters of a farm row that went through farm_roll.
create or replace function public.farm_save_counters(f public.farms)
returns void
language sql
set search_path = ''
as $$
    update public.farms
       set quest_day = f.quest_day, q_harvest = f.q_harvest, q_sold = f.q_sold, q_claimed = f.q_claimed,
           help_day = f.help_day, water_n = f.water_n, steal_n = f.steal_n,
           week_start = f.week_start, week_gold = f.week_gold,
           login_day = f.login_day, login_streak = f.login_streak
     where user_id = f.user_id
$$;

-- What the phone gets back after every action (now also the daily part).
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
        'steal_left', 10 - g.steal_n
    );
end;
$$;


-- ---------------------------------------------------------------------
-- 3. HARVEST AND SELL (5 parts per plot; daily tasks; gold of the week)
-- ---------------------------------------------------------------------

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
    end loop;

    update public.farms set plots = f.plots, store = f.store, harvested = f.harvested, updated_at = now()
     where user_id = v_me;
    perform public.farm_save_counters(f);
    return public.farm_state(f);
end;
$$;

-- Sell p_n parts of one crop, or everything when p_crop is null.
-- One part = a fifth of the price of a plot.
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
    v_got int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    f := public.farm_roll(f);

    for v_id, v_have in select key, value::int from jsonb_each_text(f.store) loop
        continue when p_crop is not null and v_id <> p_crop;
        c := public.farm_crop(v_id);
        continue when c is null;
        m := case when p_crop is null then v_have else least(v_have, greatest(coalesce(p_n, 0), 0)) end;
        continue when m <= 0;
        v_got := m * ((c ->> 'sell')::int / 5);
        f.gold := f.gold + v_got;
        f.q_sold := f.q_sold + v_got;
        f.week_gold := f.week_gold + v_got;
        f.store := jsonb_set(f.store, array[v_id], to_jsonb(v_have - m));
    end loop;

    update public.farms set gold = f.gold, store = f.store, updated_at = now() where user_id = v_me;
    perform public.farm_save_counters(f);
    return public.farm_state(f);
end;
$$;


-- ---------------------------------------------------------------------
-- 4. HỘI THAO: also counts the gold of the week (rules as in migration 35)
-- ---------------------------------------------------------------------

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
    f := public.farm_roll(f);

    n := case when f.cups_day = v_today then f.cups_n else 0 end;
    v_base := case p_place when 1 then 250 when 2 then 180 when 3 then 120 else 80 end;
    v_share := case when n < 2 then 1 when n < 10 then 0.2 else 0 end;
    v_gold := round(v_base * v_share * case when p_level = 'easy' then 0.6 else 1 end)::int;

    f.week_gold := f.week_gold + v_gold;
    perform public.farm_save_counters(f);
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
    f := public.farm_roll(f);

    n := case when f.cups_day = v_today then f.cups_n else 0 end;
    v_base := case res.place when 1 then 250 when 2 then 180 when 3 then 120 else 80 end;
    v_share := case when n < 2 then 1 when n < 10 then 0.2 else 0 end;
    v_gold := round(v_base * v_share * case when res.level = 'easy' then 0.6 else 1 end)::int;

    update public.hoithao_results set paid = true
     where room_id = res.room_id and cup_no = res.cup_no and user_id = v_me;
    f.week_gold := f.week_gold + v_gold;
    perform public.farm_save_counters(f);
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


-- ---------------------------------------------------------------------
-- 5. DAILY GIFT AND TASKS
-- ---------------------------------------------------------------------

create or replace function public.farm_daily_claim()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    v_day int;
    v_gold int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    if f.login_day = v_today then raise exception 'claimed'; end if;
    f := public.farm_roll(f);
    v_day := case when f.login_day = v_today - 1 then (f.login_streak % 7) + 1 else 1 end;
    v_gold := public.farm_login_gift(v_day);
    f.login_day := v_today; f.login_streak := v_day;
    f.week_gold := f.week_gold + v_gold;
    perform public.farm_save_counters(f);
    update public.farms set gold = gold + v_gold, updated_at = now() where user_id = v_me returning * into f;
    return public.farm_state(f) || jsonb_build_object('gift', jsonb_build_object('day', v_day, 'gold', v_gold));
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
    update public.farms set gold = gold + (q ->> 'gold')::int, updated_at = now() where user_id = v_me returning * into f;
    return public.farm_state(f) || jsonb_build_object('quest_gold', (q ->> 'gold')::int);
end;
$$;


-- ---------------------------------------------------------------------
-- 6. FRIENDS: ranking, visiting, watering, taking, what happened
-- ---------------------------------------------------------------------

-- Me and my friends who have a farm, by the gold earned this week.
create or replace function public.farm_leaderboard()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(jsonb_agg(row_to_json(t) order by t.week_gold desc, t.level desc, t.name), '[]'::jsonb)
    from (
        select f.user_id as id, f.name, f.level, f.farmer, p.display_name,
               case when f.week_start = public.farm_week() then f.week_gold else 0 end as week_gold,
               f.user_id = auth.uid() as me
        from public.farms f
        join public.profiles p on p.id = f.user_id
        where f.user_id = auth.uid() or public.hoithao_are_friends(auth.uid(), f.user_id)
    ) t
$$;

-- A friend's farm, to look at (and help or take).
create or replace function public.farm_visit(p_friend uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    f public.farms;
    g public.farms;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if p_friend = v_me then raise exception 'own_farm'; end if;
    if not public.hoithao_are_friends(v_me, p_friend) then raise exception 'not_friend'; end if;
    select * into f from public.farms where user_id = p_friend;
    if not found then raise exception 'no_farm'; end if;
    select * into g from public.farms where user_id = v_me;
    if found then g := public.farm_roll(g); end if;
    return jsonb_build_object(
        'id', f.user_id, 'name', f.name, 'farmer', f.farmer, 'level', f.level, 'plots', f.plots,
        'owner', (select display_name from public.profiles where id = p_friend),
        'server_now', public.farm_now_ms(),
        'water_left', case when g.user_id is null then 0 else 10 - g.water_n end,
        'steal_left', case when g.user_id is null then 0 else 10 - g.steal_n end
    );
end;
$$;

-- Water a growing plot of a friend: ripe 10% of its time sooner. Once per
-- plot per growing; 10 a day; the helper gets 5 gold.
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
    update public.farms set gold = gold + 5, updated_at = now() where user_id = v_me;
    return public.farm_visit(p_friend);
end;
$$;

-- Take 1 of the 5 parts of a ripe plot of a friend (once per plot; 10 a day).
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
       set store = jsonb_set(store, array[v_crop], to_jsonb(coalesce((store ->> v_crop)::int, 0) + 1)), updated_at = now()
     where user_id = v_me;
    return public.farm_visit(p_friend);
end;
$$;

-- What friends did on my farm lately (newest first).
create or replace function public.farm_news()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(jsonb_agg(row_to_json(t) order by t.at desc), '[]'::jsonb)
    from (
        select e.kind, e.crop, (extract(epoch from e.created_at) * 1000)::bigint as at,
               p.display_name as who, e.actor_id as who_id
        from public.farm_events e
        join public.profiles p on p.id = e.actor_id
        where e.owner_id = auth.uid() and e.created_at > now() - interval '3 days'
        order by e.created_at desc
        limit 30
    ) t
$$;

-- For the MayChat profile page: the farm of me or of a friend (null otherwise).
create or replace function public.farm_profile(p_user uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select jsonb_build_object('name', f.name, 'level', f.level)
    from public.farms f
    where f.user_id = p_user
      and (p_user = auth.uid() or public.hoithao_are_friends(auth.uid(), p_user))
$$;


-- ---------------------------------------------------------------------
-- 7. WHO MAY CALL THEM
-- ---------------------------------------------------------------------

revoke all on function public.farm_roll(public.farms)           from public, anon, authenticated;
revoke all on function public.farm_save_counters(public.farms)  from public, anon, authenticated;
revoke all on function public.farm_daily_claim()                from public, anon;
revoke all on function public.farm_quest_claim(int)             from public, anon;
revoke all on function public.farm_leaderboard()                from public, anon;
revoke all on function public.farm_visit(uuid)                  from public, anon;
revoke all on function public.farm_water(uuid, int)             from public, anon;
revoke all on function public.farm_steal(uuid, int)             from public, anon;
revoke all on function public.farm_news()                       from public, anon;
revoke all on function public.farm_profile(uuid)                from public, anon;

grant execute on function public.farm_daily_claim()             to authenticated;
grant execute on function public.farm_quest_claim(int)          to authenticated;
grant execute on function public.farm_leaderboard()             to authenticated;
grant execute on function public.farm_visit(uuid)               to authenticated;
grant execute on function public.farm_water(uuid, int)          to authenticated;
grant execute on function public.farm_steal(uuid, int)          to authenticated;
grant execute on function public.farm_news()                    to authenticated;
grant execute on function public.farm_profile(uuid)             to authenticated;
