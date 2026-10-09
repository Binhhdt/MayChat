-- =====================================================================
-- MayChat - migration 33: Hội thao between phones (rooms and invites)
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
-- Needs migrations 31 and 32 first.
--
-- ADDS: three tables (hoithao_rooms, hoithao_members, hoithao_results)
-- and functions whose names start with "hoithao_", plus farm_cup_room.
-- CHANGES (agreed): one-to-one messages may now also be of kind 'game'
-- (the invite card "… mời bạn vào phòng Hội thao" with a "Vào" button).
-- Nothing else of the chat is changed.
--
-- The race itself does not go through the database: the phones talk to
-- each other through a live channel ("hoithao-<room id>"). The server
-- keeps who is in a room, and the final places, so the gold of a cup
-- with friends is paid by the server.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. MESSAGES: the kind 'game' (an invite card, no file)
-- Same rule as migration 28, with 'game' added next to 'contact'.
-- ---------------------------------------------------------------------

alter table public.messages drop constraint if exists messages_kind_check;
alter table public.messages add constraint messages_kind_check check (
    kind in ('text', 'image', 'voice', 'file', 'system', 'sticker', 'location', 'contact', 'album', 'game')
    and (
        (kind in ('text', 'system', 'sticker', 'location', 'contact', 'game') and media_path is null)
        or (kind not in ('text', 'system', 'sticker', 'location', 'contact', 'game')
            and media_path is not null
            and media_path like conversation_id::text || '/%')
    )
    and (duration_ms is null or duration_ms between 0 and 600000)
    and (file_name is null or char_length(file_name) between 1 and 200)
    and (file_size is null or file_size between 0 and 5242880)
);


-- ---------------------------------------------------------------------
-- 2. ROOMS
-- ---------------------------------------------------------------------

create table if not exists public.hoithao_rooms (
    id          uuid primary key default gen_random_uuid(),
    code        text not null,
    host        uuid not null references public.profiles (id) on delete cascade,
    level       text not null default 'easy' check (level in ('easy', 'mid')),
    status      text not null default 'lobby' check (status in ('lobby', 'racing', 'done', 'closed')),
    cup_no      int not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create index if not exists hoithao_rooms_host_idx on public.hoithao_rooms (host);

create table if not exists public.hoithao_members (
    room_id    uuid not null references public.hoithao_rooms (id) on delete cascade,
    user_id    uuid not null references public.profiles (id) on delete cascade,
    joined_at  timestamptz not null default now(),
    primary key (room_id, user_id)
);
create index if not exists hoithao_members_user_idx on public.hoithao_members (user_id);

-- Final places of each cup, for the people (not the bots).
create table if not exists public.hoithao_results (
    room_id   uuid not null references public.hoithao_rooms (id) on delete cascade,
    cup_no    int not null,
    user_id   uuid not null references public.profiles (id) on delete cascade,
    place     int not null check (place between 1 and 6),
    players   int not null check (players between 2 and 6),
    humans    int not null check (humans between 1 and 6),
    level     text not null,
    paid      boolean not null default false,
    created_at timestamptz not null default now(),
    primary key (room_id, cup_no, user_id)
);

-- Nobody reads or writes these tables directly; only the functions below do.
alter table public.hoithao_rooms enable row level security;
alter table public.hoithao_members enable row level security;
alter table public.hoithao_results enable row level security;
revoke all on public.hoithao_rooms from anon, authenticated;
revoke all on public.hoithao_members from anon, authenticated;
revoke all on public.hoithao_results from anon, authenticated;

create or replace function public.hoithao_are_friends(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1 from public.friendships f
        where f.status = 'accepted'
          and f.user_a = least(a, b) and f.user_b = greatest(a, b)
    )
$$;

create or replace function public.hoithao_room_json(r public.hoithao_rooms)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'id', r.id, 'code', r.code, 'host', r.host, 'level', r.level, 'status', r.status,
        'host_name', (select p.display_name from public.profiles p where p.id = r.host)
    )
$$;


-- ---------------------------------------------------------------------
-- 3. FUNCTIONS
-- ---------------------------------------------------------------------

-- A new room; I am its host. My earlier open rooms are closed.
create or replace function public.hoithao_create(p_level text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    r public.hoithao_rooms;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    update public.hoithao_rooms set status = 'closed', updated_at = now()
     where host = v_me and status <> 'closed';
    insert into public.hoithao_rooms (code, host, level)
    values (lpad((floor(random() * 9000) + 1000)::int::text, 4, '0'), v_me,
            case when p_level = 'mid' then 'mid' else 'easy' end)
    returning * into r;
    insert into public.hoithao_members (room_id, user_id) values (r.id, v_me);
    return public.hoithao_room_json(r);
end;
$$;

-- Sends the invite card into my chat with a friend. Only the host, only
-- to a friend, at most once every 20 seconds per friend.
create or replace function public.hoithao_invite(p_room uuid, p_friend uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    r public.hoithao_rooms;
    v_conv uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into r from public.hoithao_rooms where id = p_room;
    if not found then raise exception 'no_room'; end if;
    if r.host <> v_me then raise exception 'not_host'; end if;
    if r.status = 'closed' then raise exception 'room_closed'; end if;
    if not public.hoithao_are_friends(v_me, p_friend) then raise exception 'not_friend'; end if;

    v_conv := public.get_or_create_conversation(p_friend);
    if exists (
        select 1 from public.messages m
        where m.conversation_id = v_conv and m.kind = 'game' and m.extra = p_room::text
          and m.created_at > now() - interval '20 seconds'
    ) then
        return;
    end if;
    insert into public.messages (conversation_id, sender_id, content, kind, extra)
    values (v_conv, v_me, '🏃 Mời bạn vào phòng Hội thao #' || r.code, 'game', p_room::text);
end;
$$;

-- "Vào": join the room of a friend (while it is waiting, at most 6 people).
create or replace function public.hoithao_join(p_room uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    r public.hoithao_rooms;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into r from public.hoithao_rooms where id = p_room for update;
    if not found then raise exception 'no_room'; end if;
    if r.status = 'closed' or r.updated_at < now() - interval '6 hours' then raise exception 'room_closed'; end if;
    if r.host = v_me then raise exception 'own_room'; end if;
    if not public.hoithao_are_friends(v_me, r.host) then raise exception 'not_friend'; end if;
    if not exists (select 1 from public.hoithao_members where room_id = p_room and user_id = v_me) then
        if r.status = 'racing' then raise exception 'room_racing'; end if;
        if (select count(*) from public.hoithao_members where room_id = p_room) >= 6 then
            raise exception 'room_full';
        end if;
        insert into public.hoithao_members (room_id, user_id) values (p_room, v_me);
    end if;
    return public.hoithao_room_json(r);
end;
$$;

-- Leaving; when the host leaves, the room is closed.
create or replace function public.hoithao_leave(p_room uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    delete from public.hoithao_members where room_id = p_room and user_id = v_me;
    update public.hoithao_rooms set status = 'closed', updated_at = now()
     where id = p_room and host = v_me;
end;
$$;

-- The host starts a cup: nobody new can join until it is over.
create or replace function public.hoithao_start(p_room uuid, p_level text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    update public.hoithao_rooms
       set status = 'racing', cup_no = cup_no + 1, updated_at = now(),
           level = case when p_level = 'mid' then 'mid' else 'easy' end
     where id = p_room and host = v_me and status in ('lobby', 'racing', 'done');
    if not found then raise exception 'not_host'; end if;
end;
$$;

-- The host hands in the final places of the cup (people only):
-- p_places = [{"uid": "...", "place": 1}, ...]; p_players = people + bots.
create or replace function public.hoithao_finish(p_room uuid, p_places jsonb, p_players int)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    r public.hoithao_rooms;
    e jsonb;
    v_humans int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into r from public.hoithao_rooms where id = p_room for update;
    if not found or r.host <> v_me then raise exception 'not_host'; end if;
    if r.status <> 'racing' then return; end if;   -- handed in already
    if p_players is null or p_players < 2 or p_players > 6 then raise exception 'bad_cup'; end if;

    -- only people who are really in the room count
    select count(*) into v_humans
      from jsonb_array_elements(coalesce(p_places, '[]'::jsonb)) x
     where exists (select 1 from public.hoithao_members m
                   where m.room_id = p_room and m.user_id::text = x ->> 'uid');
    if v_humans < 1 then raise exception 'bad_cup'; end if;

    for e in select * from jsonb_array_elements(p_places) loop
        if exists (select 1 from public.hoithao_members m where m.room_id = p_room and m.user_id::text = e ->> 'uid') then
            insert into public.hoithao_results (room_id, cup_no, user_id, place, players, humans, level)
            values (p_room, r.cup_no, (e ->> 'uid')::uuid,
                    least(greatest((e ->> 'place')::int, 1), p_players), p_players, v_humans, r.level)
            on conflict do nothing;
        end if;
    end loop;
    update public.hoithao_rooms set status = 'done', updated_at = now() where id = p_room;
end;
$$;

-- My gold for the last cup of a room with friends. Same rules as
-- farm_cup: 500 / 350 / 250 / 150 by place, 2 cups a day in full then
-- 20%, at most 10 paid cups a day; bots "Dễ" 60%; with friends ×1.5.
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
    v_base := case res.place when 1 then 500 when 2 then 350 when 3 then 250 else 150 end;
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


-- ---------------------------------------------------------------------
-- 4. WHO MAY CALL THEM: logged-in users only
-- ---------------------------------------------------------------------

revoke all on function public.hoithao_are_friends(uuid, uuid)          from public, anon, authenticated;
revoke all on function public.hoithao_room_json(public.hoithao_rooms)  from public, anon, authenticated;
revoke all on function public.hoithao_create(text)                     from public, anon;
revoke all on function public.hoithao_invite(uuid, uuid)               from public, anon;
revoke all on function public.hoithao_join(uuid)                       from public, anon;
revoke all on function public.hoithao_leave(uuid)                      from public, anon;
revoke all on function public.hoithao_start(uuid, text)                from public, anon;
revoke all on function public.hoithao_finish(uuid, jsonb, int)         from public, anon;
revoke all on function public.farm_cup_room(uuid)                      from public, anon;

grant execute on function public.hoithao_create(text)                  to authenticated;
grant execute on function public.hoithao_invite(uuid, uuid)            to authenticated;
grant execute on function public.hoithao_join(uuid)                    to authenticated;
grant execute on function public.hoithao_leave(uuid)                   to authenticated;
grant execute on function public.hoithao_start(uuid, text)             to authenticated;
grant execute on function public.hoithao_finish(uuid, jsonb, int)      to authenticated;
grant execute on function public.farm_cup_room(uuid)                   to authenticated;
