-- ReBand: exercise sets (a Bicep Curl session is now 2 sets of reps with a rest in between).
--
-- Requires 20261118000000_exercise_sessions.sql. Apply it BEFORE installing an app build that
-- records sets: the previous save_exercise_session() keys reps by (session, set_type, rep_index)
-- and would silently drop set 2, whose rep_index restarts at 1 (firmware/Reband/BLE_PROTOCOL.md).
-- Safe to re-run. No row is dropped; existing reps become set 1.
--
-- Security model is unchanged: SELECT-only RLS, save_exercise_session() is the only write path,
-- it inserts and never updates or deletes.

-------------------------------------------------------------------------------
-- 1. Tables
-------------------------------------------------------------------------------
do $$
begin
  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'exercise_sessions' and column_name = 'sets_completed'
  ) then
    alter table public.exercise_sessions
      add column if not exists sets_planned int not null default 1 check (sets_planned >= 1),
      add column sets_completed int not null default 0 check (sets_completed >= 0);
    -- Sessions saved before sets existed were a single set, completed when the session was.
    update public.exercise_sessions set sets_completed = 1 where status = 'completed';
  end if;
end;
$$;

-- 1-based set of the exercising arm; always 1 for the reference curl.
alter table public.exercise_reps
  add column if not exists set_index int not null default 1 check (set_index >= 1);

-- One statement, so the table is never without a primary key. Re-running rebuilds the same key.
alter table public.exercise_reps
  drop constraint if exists exercise_reps_pkey,
  add constraint exercise_reps_pkey primary key (session_id, set_type, set_index, rep_index);

-------------------------------------------------------------------------------
-- 2. Functions
-------------------------------------------------------------------------------

-- save_exercise_session(session, reps): as before, plus sets_planned / sets_completed on the
-- session and set_index on every rep. An older app build sends none of them: its session is one
-- set and its reps are set 1.
-- result: saved | already_saved
create or replace function public.save_exercise_session(p_session jsonb, p_reps jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_patient();
  sid uuid := (p_session ->> 'id')::uuid;
  owner uuid;
begin
  if sid is null then
    raise exception 'invalid_session' using errcode = '22023';
  end if;
  if jsonb_typeof(coalesce(p_reps, '[]'::jsonb)) <> 'array' or jsonb_array_length(coalesce(p_reps, '[]'::jsonb)) > 1000 then
    raise exception 'invalid_reps' using errcode = '22023';
  end if;

  select patient_id into owner from public.exercise_sessions where id = sid;
  if found then
    if owner <> uid then
      raise exception 'not_owner' using errcode = '42501';
    end if;
    return jsonb_build_object('result', 'already_saved');
  end if;

  insert into public.exercise_sessions (
    id, patient_id, exercise_type, exercising_arm, reference_arm, status,
    created_at, started_at, ended_at, duration_ms, rep_count, clean_rep_count, target_reps,
    sets_planned, sets_completed,
    reference_captured_at, reference_rom_ref_deg_x10, reference_mvc_mv, reference_baseline_mv,
    exercise_rom_ref_deg_x10, exercise_mvc_mv, exercise_baseline_mv,
    firmware_version, device_name, emg_packets_received, emg_packets_lost, device_error_flags,
    interruption_reason
  ) values (
    sid, uid,
    p_session ->> 'exercise_type',
    p_session ->> 'exercising_arm',
    p_session ->> 'reference_arm',
    p_session ->> 'status',
    (p_session ->> 'created_at')::timestamptz,
    (p_session ->> 'started_at')::timestamptz,
    (p_session ->> 'ended_at')::timestamptz,
    (p_session ->> 'duration_ms')::bigint,
    coalesce((p_session ->> 'rep_count')::int, 0),
    coalesce((p_session ->> 'clean_rep_count')::int, 0),
    coalesce((p_session ->> 'target_reps')::int, 0),
    coalesce((p_session ->> 'sets_planned')::int, 1),
    coalesce((p_session ->> 'sets_completed')::int,
      case when p_session ->> 'status' = 'completed' then 1 else 0 end),
    (p_session ->> 'reference_captured_at')::timestamptz,
    (p_session ->> 'reference_rom_ref_deg_x10')::int,
    (p_session ->> 'reference_mvc_mv')::int,
    (p_session ->> 'reference_baseline_mv')::int,
    (p_session ->> 'exercise_rom_ref_deg_x10')::int,
    (p_session ->> 'exercise_mvc_mv')::int,
    (p_session ->> 'exercise_baseline_mv')::int,
    (p_session ->> 'firmware_version')::int,
    left(p_session ->> 'device_name', 64),
    coalesce((p_session ->> 'emg_packets_received')::bigint, 0),
    coalesce((p_session ->> 'emg_packets_lost')::bigint, 0),
    coalesce((p_session ->> 'device_error_flags')::int, 0),
    left(p_session ->> 'interruption_reason', 200)
  );

  insert into public.exercise_reps (
    session_id, patient_id, set_type, set_index, rep_index, start_ms, duration_ms, rom_deg_x10,
    peak_activation_pct_mvc_x10, mean_activation_pct_mvc_x10, twist_deg, swing_deg, plane_deg, flags
  )
  select sid, uid,
    r ->> 'set_type',
    coalesce((r ->> 'set_index')::int, 1),
    (r ->> 'rep_index')::int,
    (r ->> 'start_ms')::bigint,
    (r ->> 'duration_ms')::int,
    (r ->> 'rom_deg_x10')::int,
    (r ->> 'peak_activation_pct_mvc_x10')::int,
    (r ->> 'mean_activation_pct_mvc_x10')::int,
    (r ->> 'twist_deg')::int,
    (r ->> 'swing_deg')::int,
    (r ->> 'plane_deg')::int,
    (r ->> 'flags')::int
  from jsonb_array_elements(coalesce(p_reps, '[]'::jsonb)) as r
  on conflict do nothing;

  return jsonb_build_object('result', 'saved');
end;
$$;

-- get_exercise_sessions(patient): as before. The session object now carries sets_planned and
-- sets_completed, every rep carries set_index, and reps are ordered by set, then rep.
-- result: ok (with sessions) | no_access
create or replace function public.get_exercise_sessions(p_patient_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_user();
begin
  if p_patient_id is distinct from uid and not private.clinician_can_view_patient(p_patient_id) then
    return jsonb_build_object('result', 'no_access');
  end if;
  return jsonb_build_object(
    'result', 'ok',
    'sessions', (
      select coalesce(jsonb_agg(
        to_jsonb(s) - 'patient_id' - 'uploaded_at' || jsonb_build_object(
          'reps', (
            select coalesce(jsonb_agg(to_jsonb(r) - 'patient_id' - 'session_id'
              order by r.set_type, r.set_index, r.rep_index), '[]'::jsonb)
            from public.exercise_reps r where r.session_id = s.id
          )
        ) order by coalesce(s.started_at, s.created_at) desc), '[]'::jsonb)
      from public.exercise_sessions s
      where s.patient_id = p_patient_id
    )
  );
end;
$$;

revoke all on function public.save_exercise_session(jsonb, jsonb) from public, anon;
revoke all on function public.get_exercise_sessions(uuid) from public, anon;
grant execute on function public.save_exercise_session(jsonb, jsonb) to authenticated;
grant execute on function public.get_exercise_sessions(uuid) to authenticated;
