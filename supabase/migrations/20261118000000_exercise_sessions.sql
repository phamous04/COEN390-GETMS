-- ReBand: exercise sessions (Bicep Curl) with healthy-arm reference and per-rep measurements.
--
-- Requires setup.sql and 20261116000000_clinician_patient_pairing.sql (profiles, private schema,
-- private.require_patient / require_user / require_verified_clinician,
-- private.clinician_can_view_patient).
-- Additive only: no existing table, row, policy or function is dropped or rewritten.
-- Safe to re-run.
--
-- Security model (same as pairing):
--   * Rows are owned by the patient. patient_id is always taken from auth.uid() on the server.
--   * Clients get SELECT-only RLS access; the only write path is save_exercise_session(), which
--     inserts and never updates or deletes, so a saved session can't be overwritten.
--   * Clinicians read through private.clinician_can_view_patient(), evaluated live per request:
--     removing the clinician (either side) ends access on the next read. The patient's rows are
--     not touched by disconnection.
--
-- Units follow the firmware (firmware/Reband/BLE_PROTOCOL.md): *_x10 columns are tenths
-- (degrees x10, % MVC x10), *_mv millivolts, *_ms milliseconds, *_deg whole degrees.

-------------------------------------------------------------------------------
-- 1. Tables
-------------------------------------------------------------------------------
create table if not exists public.exercise_sessions (
  id uuid primary key,                         -- generated on the phone; stable across retries
  patient_id uuid not null references public.profiles (id) on delete cascade,
  exercise_type text not null check (exercise_type in ('bicep_curl')),
  exercising_arm text not null check (exercising_arm in ('left', 'right')),
  reference_arm text not null check (reference_arm in ('left', 'right')),
  status text not null check (status in ('completed', 'incomplete', 'interrupted')),
  created_at timestamptz not null,
  started_at timestamptz,
  ended_at timestamptz,
  duration_ms bigint check (duration_ms is null or duration_ms >= 0),
  rep_count int not null default 0 check (rep_count >= 0),
  clean_rep_count int not null default 0 check (clean_rep_count >= 0),
  target_reps int not null default 0 check (target_reps >= 0),
  -- healthy-arm reference calibration (firmware Status in READY)
  reference_captured_at timestamptz,
  reference_rom_ref_deg_x10 int,
  reference_mvc_mv int,
  reference_baseline_mv int,
  -- exercising-arm calibration
  exercise_rom_ref_deg_x10 int,
  exercise_mvc_mv int,
  exercise_baseline_mv int,
  -- recording quality
  firmware_version int,
  device_name text check (device_name is null or length(device_name) <= 64),
  emg_packets_received bigint not null default 0,
  emg_packets_lost bigint not null default 0,
  device_error_flags int not null default 0,
  interruption_reason text check (interruption_reason is null or length(interruption_reason) <= 200),
  uploaded_at timestamptz not null default now(),
  constraint exercise_arms_differ check (exercising_arm <> reference_arm)
);
create index if not exists exercise_sessions_patient_time
  on public.exercise_sessions (patient_id, started_at desc);

create table if not exists public.exercise_reps (
  session_id uuid not null references public.exercise_sessions (id) on delete cascade,
  patient_id uuid not null references public.profiles (id) on delete cascade,
  set_type text not null check (set_type in ('reference', 'exercise')),
  rep_index int not null check (rep_index >= 1),
  start_ms bigint not null,
  duration_ms int not null,
  rom_deg_x10 int not null,
  peak_activation_pct_mvc_x10 int not null,
  mean_activation_pct_mvc_x10 int not null,
  twist_deg int not null,
  swing_deg int not null,
  plane_deg int not null,
  flags int not null,
  primary key (session_id, set_type, rep_index)
);
create index if not exists exercise_reps_patient on public.exercise_reps (patient_id);

-------------------------------------------------------------------------------
-- 2. Row Level Security: read-only for clients
-------------------------------------------------------------------------------
alter table public.exercise_sessions enable row level security;
alter table public.exercise_reps enable row level security;

revoke all on public.exercise_sessions from anon, authenticated;
revoke all on public.exercise_reps from anon, authenticated;
grant select on public.exercise_sessions to authenticated;
grant select on public.exercise_reps to authenticated;

drop policy if exists "exercise_sessions_select_owner_or_clinician" on public.exercise_sessions;
create policy "exercise_sessions_select_owner_or_clinician" on public.exercise_sessions
  for select to authenticated
  using (patient_id = auth.uid() or private.clinician_can_view_patient(patient_id));

drop policy if exists "exercise_reps_select_owner_or_clinician" on public.exercise_reps;
create policy "exercise_reps_select_owner_or_clinician" on public.exercise_reps
  for select to authenticated
  using (patient_id = auth.uid() or private.clinician_can_view_patient(patient_id));

-------------------------------------------------------------------------------
-- 3. Functions
-------------------------------------------------------------------------------

-- save_exercise_session(session, reps): a patient uploads one finished session.
-- Idempotent: re-sending the same id is a no-op ('already_saved'); an id owned by someone else
-- is refused. Never updates or deletes an existing row.
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
    session_id, patient_id, set_type, rep_index, start_ms, duration_ms, rom_deg_x10,
    peak_activation_pct_mvc_x10, mean_activation_pct_mvc_x10, twist_deg, swing_deg, plane_deg, flags
  )
  select sid, uid,
    r ->> 'set_type',
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

-- get_exercise_sessions(patient): the patient's own sessions, or an actively assigned verified
-- clinician's read-only view of them. Newest first, reps nested.
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
              order by r.set_type, r.rep_index), '[]'::jsonb)
            from public.exercise_reps r where r.session_id = s.id
          )
        ) order by coalesce(s.started_at, s.created_at) desc), '[]'::jsonb)
      from public.exercise_sessions s
      where s.patient_id = p_patient_id
    )
  );
end;
$$;

-- Last completed session per active patient, for the clinician dashboard.
create or replace function public.get_assigned_patients_activity()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_verified_clinician();
begin
  return (
    select coalesce(jsonb_agg(jsonb_build_object(
        'patient_id', a.patient_id,
        'last_completed_at', (
          select max(coalesce(s.started_at, s.created_at)) from public.exercise_sessions s
          where s.patient_id = a.patient_id and s.status = 'completed'
        ),
        'completed_sessions', (
          select count(*) from public.exercise_sessions s
          where s.patient_id = a.patient_id and s.status = 'completed'
        )
      )), '[]'::jsonb)
    from public.patient_clinician_assignments a
    where a.clinician_id = uid and a.status = 'active'
  );
end;
$$;

revoke all on function public.save_exercise_session(jsonb, jsonb) from public, anon;
revoke all on function public.get_exercise_sessions(uuid) from public, anon;
revoke all on function public.get_assigned_patients_activity() from public, anon;
grant execute on function public.save_exercise_session(jsonb, jsonb) to authenticated;
grant execute on function public.get_exercise_sessions(uuid) to authenticated;
grant execute on function public.get_assigned_patients_activity() to authenticated;
