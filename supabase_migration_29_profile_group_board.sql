-- =====================================================================
-- MayChat - migration 29: profile page, group polls / notes / reminders
--
-- How: Supabase Dashboard -> SQL Editor -> New query -> paste ALL of this
-- file -> Run. Expected: "Success. No rows returned". Safe to run again.
--
-- ADDS only: three new columns on profiles, four new tables and their
-- functions. No existing column, rule, trigger or function is changed.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. PROFILE PAGE: cover picture, introduction, birthday
-- ---------------------------------------------------------------------

alter table public.profiles add column if not exists cover_path text;
alter table public.profiles add column if not exists bio text;
alter table public.profiles add column if not exists birthday date;

alter table public.profiles drop constraint if exists profiles_bio_check;
alter table public.profiles add constraint profiles_bio_check
    check (bio is null or char_length(bio) <= 300);

-- Saves the three new things of MY profile.
--   p_bio:        the introduction ('' = none)
--   p_birthday:   'YYYY-MM-DD' ('' = none)
--   p_cover_path: null = keep the cover, '' = remove it, otherwise a file
--                 inside MY folder of the "avatars" storage.
create or replace function public.update_my_profile_details(p_bio text, p_birthday text, p_cover_path text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me  uuid := auth.uid();
    v_bio text := trim(coalesce(p_bio, ''));
    v_day date;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if char_length(v_bio) > 300 then raise exception 'invalid_bio'; end if;
    if coalesce(p_birthday, '') <> '' then
        begin
            v_day := p_birthday::date;
        exception when others then
            raise exception 'invalid_birthday';
        end;
        if v_day > current_date or v_day < date '1900-01-01' then
            raise exception 'invalid_birthday';
        end if;
    end if;
    if p_cover_path is not null and p_cover_path <> ''
       and p_cover_path not like v_me::text || '/%' then
        raise exception 'invalid_cover_path';
    end if;

    update public.profiles
    set bio = nullif(v_bio, ''),
        birthday = v_day,
        cover_path = case
            when p_cover_path is null then cover_path
            when p_cover_path = '' then null
            else p_cover_path
        end
    where id = v_me;
end;
$$;

revoke all on function public.update_my_profile_details(text, text, text) from public, anon;
grant execute on function public.update_my_profile_details(text, text, text) to authenticated;


-- ---------------------------------------------------------------------
-- 2. TABLES OF THE GROUP BOARD
-- ---------------------------------------------------------------------

create table if not exists public.group_polls (
    id          uuid primary key default gen_random_uuid(),
    group_id    uuid not null references public.groups (id) on delete cascade,
    creator_id  uuid not null references public.profiles (id) on delete cascade,
    question    text not null check (char_length(question) between 1 and 200),
    options     text[] not null check (array_length(options, 1) between 2 and 10),
    multiple    boolean not null default false,     -- may tick several options
    closed_at   timestamptz,                        -- not empty = voting is over
    created_at  timestamptz not null default now()
);
create index if not exists group_polls_group_idx on public.group_polls (group_id, created_at desc);

create table if not exists public.group_poll_votes (
    poll_id       uuid not null references public.group_polls (id) on delete cascade,
    user_id       uuid not null references public.profiles (id) on delete cascade,
    option_index  integer not null check (option_index between 0 and 9),
    created_at    timestamptz not null default now(),
    primary key (poll_id, user_id, option_index)
);

create table if not exists public.group_notes (
    id          uuid primary key default gen_random_uuid(),
    group_id    uuid not null references public.groups (id) on delete cascade,
    author_id   uuid not null references public.profiles (id) on delete cascade,
    content     text not null check (char_length(content) between 1 and 2000),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create index if not exists group_notes_group_idx on public.group_notes (group_id, updated_at desc);

create table if not exists public.group_reminders (
    id          uuid primary key default gen_random_uuid(),
    group_id    uuid not null references public.groups (id) on delete cascade,
    creator_id  uuid not null references public.profiles (id) on delete cascade,
    title       text not null check (char_length(title) between 1 and 200),
    remind_at   timestamptz not null,
    created_at  timestamptz not null default now()
);
create index if not exists group_reminders_group_idx on public.group_reminders (group_id, remind_at);

alter table public.group_polls      enable row level security;
alter table public.group_poll_votes enable row level security;
alter table public.group_notes      enable row level security;
alter table public.group_reminders  enable row level security;

-- The app may only READ these tables; every change goes through the
-- functions below, which check who is asking.
revoke all on public.group_polls      from anon, authenticated;
revoke all on public.group_poll_votes from anon, authenticated;
revoke all on public.group_notes      from anon, authenticated;
revoke all on public.group_reminders  from anon, authenticated;
grant select on public.group_polls      to authenticated;
grant select on public.group_poll_votes to authenticated;
grant select on public.group_notes      to authenticated;
grant select on public.group_reminders  to authenticated;

drop policy if exists "group_polls: members can read" on public.group_polls;
create policy "group_polls: members can read"
    on public.group_polls for select to authenticated
    using (public.is_group_member(group_id));

drop policy if exists "group_poll_votes: members can read" on public.group_poll_votes;
create policy "group_poll_votes: members can read"
    on public.group_poll_votes for select to authenticated
    using (exists (
        select 1 from public.group_polls p
        where p.id = poll_id and public.is_group_member(p.group_id)
    ));

drop policy if exists "group_notes: members can read" on public.group_notes;
create policy "group_notes: members can read"
    on public.group_notes for select to authenticated
    using (public.is_group_member(group_id));

drop policy if exists "group_reminders: members can read" on public.group_reminders;
create policy "group_reminders: members can read"
    on public.group_reminders for select to authenticated
    using (public.is_group_member(group_id));


-- ---------------------------------------------------------------------
-- 3. POLLS
-- Creating one also puts a message into the group ("📊 Bình chọn: ...")
-- so that everybody is told; its "extra" says which poll it is.
-- ---------------------------------------------------------------------

create or replace function public.create_group_poll(
    p_group uuid, p_question text, p_options text[], p_multiple boolean
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me       uuid := auth.uid();
    v_question text := trim(coalesce(p_question, ''));
    v_options  text[] := '{}';
    v_one      text;
    v_id       uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;
    if char_length(v_question) < 1 or char_length(v_question) > 200 then
        raise exception 'invalid_poll';
    end if;
    foreach v_one in array coalesce(p_options, '{}'::text[]) loop
        v_one := trim(coalesce(v_one, ''));
        if v_one <> '' then
            if char_length(v_one) > 100 then raise exception 'invalid_poll'; end if;
            v_options := v_options || v_one;
        end if;
    end loop;
    if coalesce(array_length(v_options, 1), 0) < 2 or array_length(v_options, 1) > 10 then
        raise exception 'invalid_poll';
    end if;

    insert into public.group_polls (group_id, creator_id, question, options, multiple)
    values (p_group, v_me, v_question, v_options, coalesce(p_multiple, false))
    returning id into v_id;

    insert into public.group_messages (group_id, sender_id, content, kind, extra)
    values (p_group, v_me, '📊 Bình chọn: ' || v_question, 'text', 'poll:' || v_id::text);
    return v_id;
end;
$$;

-- My vote: p_options are the options I tick now (they replace what I
-- ticked before; an empty list takes my vote back).
create or replace function public.vote_group_poll(p_poll uuid, p_options integer[])
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_poll  public.group_polls%rowtype;
    v_picks integer[];
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into v_poll from public.group_polls where id = p_poll;
    if not found or not public.is_group_member(v_poll.group_id) then raise exception 'not_allowed'; end if;
    if v_poll.closed_at is not null then raise exception 'poll_closed'; end if;

    select coalesce(array_agg(distinct x), '{}') into v_picks
    from unnest(coalesce(p_options, '{}'::integer[])) as x
    where x >= 0 and x < array_length(v_poll.options, 1);
    if not v_poll.multiple and coalesce(array_length(v_picks, 1), 0) > 1 then
        raise exception 'invalid_poll';
    end if;

    delete from public.group_poll_votes where poll_id = p_poll and user_id = v_me;
    insert into public.group_poll_votes (poll_id, user_id, option_index)
    select p_poll, v_me, x from unnest(v_picks) as x;
end;
$$;

-- Ends the voting. Only who made the poll, the leader or a deputy.
create or replace function public.close_group_poll(p_poll uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_poll public.group_polls%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into v_poll from public.group_polls where id = p_poll;
    if not found or not public.is_group_member(v_poll.group_id) then raise exception 'not_allowed'; end if;
    if v_poll.creator_id <> v_me and not public.is_group_manager(v_poll.group_id) then
        raise exception 'not_allowed';
    end if;
    update public.group_polls set closed_at = now() where id = p_poll and closed_at is null;
end;
$$;

create or replace function public.delete_group_poll(p_poll uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_poll public.group_polls%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into v_poll from public.group_polls where id = p_poll;
    if not found or not public.is_group_member(v_poll.group_id) then raise exception 'not_allowed'; end if;
    if v_poll.creator_id <> v_me and not public.is_group_manager(v_poll.group_id) then
        raise exception 'not_allowed';
    end if;
    delete from public.group_polls where id = p_poll;
end;
$$;


-- ---------------------------------------------------------------------
-- 4. NOTES
-- ---------------------------------------------------------------------

create or replace function public.create_group_note(p_group uuid, p_content text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me      uuid := auth.uid();
    v_content text := trim(coalesce(p_content, ''));
    v_id      uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;
    if char_length(v_content) < 1 or char_length(v_content) > 2000 then
        raise exception 'invalid_note';
    end if;

    insert into public.group_notes (group_id, author_id, content)
    values (p_group, v_me, v_content)
    returning id into v_id;

    insert into public.group_messages (group_id, sender_id, content, kind, extra)
    values (
        p_group, v_me,
        '📝 Ghi chú: ' || left(v_content, 300) || case when char_length(v_content) > 300 then '…' else '' end,
        'text', 'note:' || v_id::text
    );
    return v_id;
end;
$$;

-- Only who wrote the note, the leader or a deputy.
create or replace function public.update_group_note(p_note uuid, p_content text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me      uuid := auth.uid();
    v_note    public.group_notes%rowtype;
    v_content text := trim(coalesce(p_content, ''));
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into v_note from public.group_notes where id = p_note;
    if not found or not public.is_group_member(v_note.group_id) then raise exception 'not_allowed'; end if;
    if v_note.author_id <> v_me and not public.is_group_manager(v_note.group_id) then
        raise exception 'not_allowed';
    end if;
    if char_length(v_content) < 1 or char_length(v_content) > 2000 then
        raise exception 'invalid_note';
    end if;
    update public.group_notes set content = v_content, updated_at = now() where id = p_note;
end;
$$;

create or replace function public.delete_group_note(p_note uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me   uuid := auth.uid();
    v_note public.group_notes%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into v_note from public.group_notes where id = p_note;
    if not found or not public.is_group_member(v_note.group_id) then raise exception 'not_allowed'; end if;
    if v_note.author_id <> v_me and not public.is_group_manager(v_note.group_id) then
        raise exception 'not_allowed';
    end if;
    delete from public.group_notes where id = p_note;
end;
$$;


-- ---------------------------------------------------------------------
-- 5. REMINDERS
-- p_when_text is the time written the way people read it ("20/10 08:00"),
-- made by the phone; it is only used in the message put into the group.
-- ---------------------------------------------------------------------

create or replace function public.create_group_reminder(
    p_group uuid, p_title text, p_at timestamptz, p_when_text text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me    uuid := auth.uid();
    v_title text := trim(coalesce(p_title, ''));
    v_when  text := left(trim(coalesce(p_when_text, '')), 40);
    v_id    uuid;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    if not public.is_group_member(p_group) then raise exception 'not_allowed'; end if;
    if char_length(v_title) < 1 or char_length(v_title) > 200 then
        raise exception 'invalid_reminder';
    end if;
    if p_at is null or p_at <= now() or p_at > now() + interval '2 years' then
        raise exception 'invalid_reminder_time';
    end if;

    insert into public.group_reminders (group_id, creator_id, title, remind_at)
    values (p_group, v_me, v_title, p_at)
    returning id into v_id;

    insert into public.group_messages (group_id, sender_id, content, kind, extra)
    values (
        p_group, v_me,
        '⏰ Nhắc hẹn: ' || v_title || case when v_when <> '' then ' (' || v_when || ')' else '' end,
        'text', 'remind:' || v_id::text
    );
    return v_id;
end;
$$;

-- Only who made the reminder, the leader or a deputy.
create or replace function public.delete_group_reminder(p_reminder uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me  uuid := auth.uid();
    v_row public.group_reminders%rowtype;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into v_row from public.group_reminders where id = p_reminder;
    if not found or not public.is_group_member(v_row.group_id) then raise exception 'not_allowed'; end if;
    if v_row.creator_id <> v_me and not public.is_group_manager(v_row.group_id) then
        raise exception 'not_allowed';
    end if;
    delete from public.group_reminders where id = p_reminder;
end;
$$;


-- ---------------------------------------------------------------------
-- 6. WHO MAY CALL THE NEW FUNCTIONS: logged-in users only
-- ---------------------------------------------------------------------

revoke all on function public.create_group_poll(uuid, text, text[], boolean) from public, anon;
revoke all on function public.vote_group_poll(uuid, integer[]) from public, anon;
revoke all on function public.close_group_poll(uuid) from public, anon;
revoke all on function public.delete_group_poll(uuid) from public, anon;
revoke all on function public.create_group_note(uuid, text) from public, anon;
revoke all on function public.update_group_note(uuid, text) from public, anon;
revoke all on function public.delete_group_note(uuid) from public, anon;
revoke all on function public.create_group_reminder(uuid, text, timestamptz, text) from public, anon;
revoke all on function public.delete_group_reminder(uuid) from public, anon;

grant execute on function public.create_group_poll(uuid, text, text[], boolean) to authenticated;
grant execute on function public.vote_group_poll(uuid, integer[]) to authenticated;
grant execute on function public.close_group_poll(uuid) to authenticated;
grant execute on function public.delete_group_poll(uuid) to authenticated;
grant execute on function public.create_group_note(uuid, text) to authenticated;
grant execute on function public.update_group_note(uuid, text) to authenticated;
grant execute on function public.delete_group_note(uuid) to authenticated;
grant execute on function public.create_group_reminder(uuid, text, timestamptz, text) to authenticated;
grant execute on function public.delete_group_reminder(uuid) to authenticated;
