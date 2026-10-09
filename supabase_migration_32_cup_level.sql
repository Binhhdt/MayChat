-- =====================================================================
-- MayChat - migration 32: Hội thao reward by bot strength
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Needs migration 31 first.
--
-- CHANGES only farm_cup (agreed): the bots of a cup are now "Dễ" or
-- "Vừa". "Vừa" pays the gold as before; "Dễ" pays 60%. Everything else
-- of the farm is unchanged.
-- =====================================================================

drop function if exists public.farm_cup(int, int, int);

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
    v_base := case p_place when 1 then 500 when 2 then 350 when 3 then 250 else 150 end;
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

revoke all on function public.farm_cup(int, int, int, text) from public, anon;
grant execute on function public.farm_cup(int, int, int, text) to authenticated;
