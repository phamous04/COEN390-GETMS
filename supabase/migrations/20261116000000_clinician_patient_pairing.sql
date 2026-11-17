-- ReBand: clinician–patient pairing (reusable invitation code + explicit patient consent).
--
-- Requires supabase/setup.sql (profiles table, recordings bucket) to have been applied first.
-- Additive only: no existing table, row, policy or Storage object is dropped or rewritten.
-- Safe to re-run (idempotent `if not exists` / `create or replace` / `drop policy if exists`).
--
-- Security model
--   * The invitation code is only an identifier for a clinician. It never grants access by itself:
--     a patient must call confirm_clinician_connection (explicit consent), and access is then
--     decided per request from the live patient_clinician_assignments row (never from JWT claims).
--   * Every write goes through the SECURITY DEFINER functions below, which read the caller from
--     auth.uid() and verify role/verification server-side. Clients get SELECT-only RLS access.
--   * Helpers and the rate-limit log live in schema `private`, which PostgREST does not expose.

-------------------------------------------------------------------------------
-- 0. Private schema for helpers that must not be callable through the REST API
-------------------------------------------------------------------------------
create schema if not exists private;
revoke all on schema private from public, anon;
grant usage on schema private to authenticated; -- needed so RLS policies can call helpers

-------------------------------------------------------------------------------
-- 1. Clinician verification
--    role = 'clinician' AND clinician_verified_at IS NOT NULL  => verified clinician
--    role = 'clinician' AND clinician_verified_at IS NULL      => pending verification (no access)
--    Only an administrator (SQL editor / service role) can set either column: the existing
--    column-level grant lets users update `full_name` only.
-------------------------------------------------------------------------------
do $$
begin
  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'profiles' and column_name = 'clinician_verified_at'
  ) then
    alter table public.profiles add column clinician_verified_at timestamptz;
    -- Clinicians that existed before this migration were promoted manually by an admin
    -- (see supabase/README.md), so they are already verified. Runs only when the column is new.
    update public.profiles set clinician_verified_at = now() where role = 'clinician';
  end if;
end $$;

-- Sign-up may *request* a clinician account (account_type = 'clinician' in user metadata).
-- That only creates an unverified clinician profile, which has no pairing or patient access
-- until an administrator sets clinician_verified_at.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, email, full_name, role)
  values (
    new.id,
    new.email,
    left(nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''), 80),
    case when new.raw_user_meta_data ->> 'account_type' = 'clinician' then 'clinician' else 'patient' end
  );
  return new;
end;
$$;

create or replace function private.is_verified_clinician(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.profiles
    where id = p_user and role = 'clinician' and clinician_verified_at is not null
  );
$$;

-------------------------------------------------------------------------------
-- 2. Tables
-------------------------------------------------------------------------------

-- One row per clinician = one active code. Regenerating overwrites `code`, so the old code
-- stops resolving in the same statement. Assignments do not reference the code.
create table if not exists public.clinician_invite_codes (
  clinician_id uuid primary key references public.profiles (id) on delete cascade,
  code text not null unique check (code ~ '^[A-HJ-NP-Z2-9]{8}$'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Relationship history. Rows are never deleted by the app: ending a relationship sets a status.
--   pending   patient consented, waiting for clinician approval (patient was previously removed)
--   active    clinician may view the patient's permitted data
--   revoked   ended by the patient or the clinician (see revoked_by_role)
--   rejected  clinician declined a pending reconnection request
--   cancelled patient withdrew a pending request (or replaced it with another clinician)
-- revoked_at / revoked_by / revoked_by_role record when and by whom any row was ended.
create table if not exists public.patient_clinician_assignments (
  id uuid primary key default gen_random_uuid(),
  patient_id uuid not null references public.profiles (id) on delete cascade,
  clinician_id uuid not null references public.profiles (id) on delete cascade,
  status text not null check (status in ('pending', 'active', 'revoked', 'rejected', 'cancelled')),
  patient_consented_at timestamptz not null,
  clinician_approved_at timestamptz,
  revoked_at timestamptz,
  revoked_by uuid references public.profiles (id) on delete set null,
  revoked_by_role text check (revoked_by_role in ('patient', 'clinician')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint assignment_not_self check (patient_id <> clinician_id),
  constraint assignment_end_recorded check (
    (status in ('pending', 'active')) = (revoked_at is null)
  )
);

-- Initial rule: a patient has at most one active clinician and at most one pending request.
-- To allow several clinicians per patient later, drop only `assignments_one_active_per_patient`.
create unique index if not exists assignments_one_active_per_patient
  on public.patient_clinician_assignments (patient_id) where status = 'active';
create unique index if not exists assignments_one_pending_per_patient
  on public.patient_clinician_assignments (patient_id) where status = 'pending';
create unique index if not exists assignments_one_open_per_pair
  on public.patient_clinician_assignments (patient_id, clinician_id) where status in ('pending', 'active');
create index if not exists assignments_clinician_status
  on public.patient_clinician_assignments (clinician_id, status);

-- Lookup/connection attempts, for rate limiting code guessing. Not exposed to clients.
create table if not exists private.pairing_attempts (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  succeeded boolean not null,
  attempted_at timestamptz not null default now()
);
create index if not exists pairing_attempts_user_time on private.pairing_attempts (user_id, attempted_at);
revoke all on private.pairing_attempts from public, anon, authenticated;
-- Defence in depth: no policies, so even a mistaken grant exposes nothing. The SECURITY DEFINER
-- functions that use it run as the table owner and are not affected.
alter table private.pairing_attempts enable row level security;

-------------------------------------------------------------------------------
-- 3. Row Level Security (read-only for clients; writes only via functions)
-------------------------------------------------------------------------------
alter table public.clinician_invite_codes enable row level security;
alter table public.patient_clinician_assignments enable row level security;

revoke all on public.clinician_invite_codes from anon, authenticated;
revoke all on public.patient_clinician_assignments from anon, authenticated;
grant select on public.clinician_invite_codes to authenticated;
grant select on public.patient_clinician_assignments to authenticated;

-- A verified clinician sees only their own code. Nobody can list other clinicians' codes.
drop policy if exists "invite_codes_select_own" on public.clinician_invite_codes;
create policy "invite_codes_select_own" on public.clinician_invite_codes
  for select to authenticated
  using (clinician_id = auth.uid() and private.is_verified_clinician(auth.uid()));

-- Each party sees only relationships they belong to.
drop policy if exists "assignments_select_participant" on public.patient_clinician_assignments;
create policy "assignments_select_participant" on public.patient_clinician_assignments
  for select to authenticated
  using (
    patient_id = auth.uid()
    or (clinician_id = auth.uid() and private.is_verified_clinician(auth.uid()))
  );

-- The single authorization rule for clinician access to patient data. Evaluated live on every
-- request, so revocation (or loss of verification) takes effect for the next read immediately.
-- Future patient-data tables should use it in their SELECT policies, e.g.
--   using (patient_id = auth.uid() or private.clinician_can_view_patient(patient_id))
create or replace function private.clinician_can_view_patient(p_patient uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select auth.uid() is not null
    and private.is_verified_clinician(auth.uid())
    and exists (
      select 1 from public.patient_clinician_assignments a
      where a.clinician_id = auth.uid() and a.patient_id = p_patient and a.status = 'active'
    );
$$;

-- Same rule for a Storage folder name (`<patient_id>/...`), compared as text so a malformed
-- folder name can never raise a cast error.
create or replace function private.clinician_can_view_patient_folder(p_folder text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select auth.uid() is not null
    and private.is_verified_clinician(auth.uid())
    and exists (
      select 1 from public.patient_clinician_assignments a
      where a.clinician_id = auth.uid() and a.patient_id::text = p_folder and a.status = 'active'
    );
$$;

revoke all on function private.is_verified_clinician(uuid) from public, anon;
revoke all on function private.clinician_can_view_patient(uuid) from public, anon;
revoke all on function private.clinician_can_view_patient_folder(text) from public, anon;
grant execute on function private.is_verified_clinician(uuid) to authenticated;
grant execute on function private.clinician_can_view_patient(uuid) to authenticated;
grant execute on function private.clinician_can_view_patient_folder(text) to authenticated;

-- Storage: an actively assigned, verified clinician may READ (never write or delete) the
-- patient's recordings folder. The existing own-folder policies are unchanged.
-- Signed URLs remain valid until they expire even after revocation, so the app must request
-- short-lived URLs (60 s or less) at the moment of viewing and never cache them.
drop policy if exists "recordings_select_assigned_clinician" on storage.objects;
create policy "recordings_select_assigned_clinician" on storage.objects
  for select to authenticated
  using (
    bucket_id = 'recordings'
    and private.clinician_can_view_patient_folder((storage.foldername(name))[1])
  );

-------------------------------------------------------------------------------
-- 4. Internal helpers (not granted to clients; called from SECURITY DEFINER functions)
-------------------------------------------------------------------------------
create or replace function private.require_user()
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  return uid;
end;
$$;

create or replace function private.require_verified_clinician()
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_user();
begin
  if not private.is_verified_clinician(uid) then
    raise exception 'not_verified_clinician' using errcode = '42501';
  end if;
  return uid;
end;
$$;

-- Removing a patient is always allowed for a clinician account, verified or not: it only
-- reduces access.
create or replace function private.require_clinician()
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_user();
begin
  if not exists (select 1 from public.profiles where id = uid and role = 'clinician') then
    raise exception 'not_clinician' using errcode = '42501';
  end if;
  return uid;
end;
$$;

create or replace function private.require_patient()
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_user();
begin
  if not exists (select 1 from public.profiles where id = uid and role = 'patient') then
    raise exception 'not_patient' using errcode = '42501';
  end if;
  return uid;
end;
$$;

-- 8 symbols from a 32-symbol alphabet without look-alikes (no I, O, 0, 1): 2^40 combinations.
-- Each symbol uses the first byte of a fresh v4 UUID (cryptographically random, fully random
-- byte); 256 is a multiple of 32, so `% 32` is unbiased.
create or replace function private.new_invite_code()
returns text
language plpgsql
volatile
set search_path = ''
as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  result text := '';
begin
  for i in 1..8 loop
    result := result || substr(alphabet, get_byte(uuid_send(gen_random_uuid()), 0) % 32 + 1, 1);
  end loop;
  return result;
end;
$$;

create or replace function private.normalize_code(p_code text)
returns text
language sql
immutable
set search_path = ''
as $$
  select upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
$$;

create or replace function private.mask_email(p_email text)
returns text
language sql
immutable
set search_path = ''
as $$
  select case
    when p_email is null or position('@' in p_email) < 2 then null
    else left(p_email, 1) || '***' || substr(p_email, position('@' in p_email))
  end;
$$;

-- Writes (or keeps) the clinician's code, retrying on the rare collision with another code.
create or replace function private.set_invite_code(p_clinician uuid, p_replace boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  rec public.clinician_invite_codes;
begin
  for attempt in 1..10 loop
    begin
      if p_replace then
        insert into public.clinician_invite_codes as c (clinician_id, code)
        values (p_clinician, private.new_invite_code())
        on conflict (clinician_id) do update set code = excluded.code, updated_at = now()
        returning * into rec;
      else
        insert into public.clinician_invite_codes (clinician_id, code)
        values (p_clinician, private.new_invite_code())
        on conflict (clinician_id) do nothing;
        select * into rec from public.clinician_invite_codes where clinician_id = p_clinician;
      end if;
      return jsonb_build_object('code', rec.code, 'updated_at', rec.updated_at);
    exception when unique_violation then
      -- the random code matched another clinician's code: try a new one
    end;
  end loop;
  raise exception 'code_generation_failed';
end;
$$;

-- 10 failed or 40 total attempts per 15 minutes per account.
create or replace function private.pairing_rate_limited(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select
    (select count(*) from private.pairing_attempts
      where user_id = p_user and not succeeded and attempted_at > now() - interval '15 minutes') >= 10
    or
    (select count(*) from private.pairing_attempts
      where user_id = p_user and attempted_at > now() - interval '15 minutes') >= 40;
$$;

create or replace function private.log_pairing_attempt(p_user uuid, p_succeeded boolean)
returns void
language sql
security definer
set search_path = ''
as $$
  delete from private.pairing_attempts where user_id = p_user and attempted_at < now() - interval '1 day';
  insert into private.pairing_attempts (user_id, succeeded) values (p_user, p_succeeded);
$$;

create or replace function private.clinician_for_code(p_code text)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select c.clinician_id
  from public.clinician_invite_codes c
  join public.profiles p on p.id = c.clinician_id
  where c.code = private.normalize_code(p_code)
    and p.role = 'clinician' and p.clinician_verified_at is not null;
$$;

-- How a confirmation between this patient and clinician would be handled:
--   already_connected | already_pending | needs_approval | direct
-- needs_approval when the latest *ended* relationship (ignoring withdrawn requests) was ended by
-- the clinician (removed or rejected): the patient may ask again, but the clinician must approve.
create or replace function private.connection_kind(p_patient uuid, p_clinician uuid)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  last_end public.patient_clinician_assignments;
begin
  if exists (select 1 from public.patient_clinician_assignments
             where patient_id = p_patient and clinician_id = p_clinician and status = 'active') then
    return 'already_connected';
  end if;
  if exists (select 1 from public.patient_clinician_assignments
             where patient_id = p_patient and clinician_id = p_clinician and status = 'pending') then
    return 'already_pending';
  end if;
  select * into last_end from public.patient_clinician_assignments
  where patient_id = p_patient and clinician_id = p_clinician and status in ('revoked', 'rejected')
  order by revoked_at desc
  limit 1;
  if found and (last_end.status = 'rejected' or last_end.revoked_by_role = 'clinician') then
    return 'needs_approval';
  end if;
  return 'direct';
end;
$$;

create or replace function private.display_name(p_user uuid)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(nullif(btrim(full_name), ''), 'ReBand user') from public.profiles where id = p_user;
$$;

-------------------------------------------------------------------------------
-- 5. Client API (PostgREST RPC). Every function derives the caller from auth.uid().
-------------------------------------------------------------------------------

-- Backend-verified account type, used by the app to choose patient or clinician screens.
create or replace function public.get_my_account()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_user();
  p public.profiles;
begin
  select * into p from public.profiles where id = uid;
  if not found then
    raise exception 'profile_missing';
  end if;
  return jsonb_build_object(
    'user_id', p.id,
    'email', p.email,
    'full_name', p.full_name,
    'role', p.role,
    'clinician_verified', p.role = 'clinician' and p.clinician_verified_at is not null
  );
end;
$$;

create or replace function public.set_my_full_name(p_full_name text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_user();
  clean text := btrim(coalesce(p_full_name, ''));
begin
  if length(clean) < 1 or length(clean) > 80 then
    raise exception 'invalid_name' using errcode = '22023';
  end if;
  update public.profiles set full_name = clean where id = uid;
  return jsonb_build_object('full_name', clean);
end;
$$;

-- getMyClinicianCode(): null when the clinician has not generated one yet.
create or replace function public.get_my_clinician_code()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_verified_clinician();
  rec public.clinician_invite_codes;
begin
  select * into rec from public.clinician_invite_codes where clinician_id = uid;
  if not found then
    return null;
  end if;
  return jsonb_build_object('code', rec.code, 'updated_at', rec.updated_at);
end;
$$;

-- generateClinicianCode(): returns the existing code if there is one (idempotent).
create or replace function public.generate_clinician_code()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  return private.set_invite_code(private.require_verified_clinician(), false);
end;
$$;

-- regenerateClinicianCode(): atomically replaces the code; existing assignments are untouched.
create or replace function public.regenerate_clinician_code()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  return private.set_invite_code(private.require_verified_clinician(), true);
end;
$$;

-- lookupClinicianCode(code): identifies the clinician so the patient can decide. Creates nothing.
-- result: found | invalid_code | rate_limited
create or replace function public.lookup_clinician_code(p_code text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_patient();
  cid uuid;
  other record;
  p public.profiles;
begin
  if private.pairing_rate_limited(uid) then
    return jsonb_build_object('result', 'rate_limited');
  end if;
  cid := private.clinician_for_code(p_code);
  perform private.log_pairing_attempt(uid, cid is not null);
  if cid is null then
    return jsonb_build_object('result', 'invalid_code');
  end if;

  select * into p from public.profiles where id = cid;
  select a.clinician_id, private.display_name(a.clinician_id) as name into other
  from public.patient_clinician_assignments a
  where a.patient_id = uid and a.status = 'active' and a.clinician_id <> cid;

  return jsonb_build_object(
    'result', 'found',
    'clinician_name', private.display_name(cid),
    'clinician_email_hint', private.mask_email(p.email),
    'connection', private.connection_kind(uid, cid),
    'current_clinician_name', other.name
  );
end;
$$;

-- confirmClinicianConnection(code) and requestReconnection(code): called only after the patient
-- explicitly confirms the consent screen. The code is re-validated here, so a code regenerated
-- in between is rejected. Depending on history it creates an active assignment (direct) or a
-- pending request that the clinician must approve (previously removed or rejected).
-- If the patient already has another active clinician, p_replace_current must be true
-- (the app shows the switch explanation first); the old relationship is then revoked in the
-- same transaction, so two active clinicians can never coexist.
-- result: connected | pending_approval | already_connected | has_active_clinician
--         | invalid_code | rate_limited
create or replace function public.confirm_clinician_connection(p_code text, p_replace_current boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_patient();
  cid uuid;
  kind text;
  other public.patient_clinician_assignments;
begin
  -- Serialize all pairing changes for this patient.
  perform 1 from public.profiles where id = uid for update;

  if private.pairing_rate_limited(uid) then
    return jsonb_build_object('result', 'rate_limited');
  end if;
  cid := private.clinician_for_code(p_code);
  perform private.log_pairing_attempt(uid, cid is not null);
  if cid is null then
    return jsonb_build_object('result', 'invalid_code');
  end if;

  kind := private.connection_kind(uid, cid);
  if kind = 'already_connected' then
    return jsonb_build_object('result', 'already_connected', 'clinician_name', private.display_name(cid));
  elsif kind = 'already_pending' then
    return jsonb_build_object('result', 'pending_approval', 'clinician_name', private.display_name(cid));
  end if;

  select * into other from public.patient_clinician_assignments
  where patient_id = uid and status = 'active' and clinician_id <> cid;
  if found then
    if not coalesce(p_replace_current, false) then
      return jsonb_build_object('result', 'has_active_clinician',
                                'current_clinician_name', private.display_name(other.clinician_id));
    end if;
    update public.patient_clinician_assignments
    set status = 'revoked', revoked_at = now(), revoked_by = uid, revoked_by_role = 'patient', updated_at = now()
    where id = other.id;
  end if;

  -- A new choice withdraws any other outstanding request from this patient.
  update public.patient_clinician_assignments
  set status = 'cancelled', revoked_at = now(), revoked_by = uid, revoked_by_role = 'patient', updated_at = now()
  where patient_id = uid and status = 'pending';

  if kind = 'needs_approval' then
    insert into public.patient_clinician_assignments (patient_id, clinician_id, status, patient_consented_at)
    values (uid, cid, 'pending', now());
    return jsonb_build_object('result', 'pending_approval', 'clinician_name', private.display_name(cid));
  end if;

  insert into public.patient_clinician_assignments (patient_id, clinician_id, status, patient_consented_at)
  values (uid, cid, 'active', now());
  return jsonb_build_object('result', 'connected', 'clinician_name', private.display_name(cid));
end;
$$;

-- getMyClinicians(): the patient's active and pending relationships, plus the most recent ended
-- one so the app can explain a revocation or rejection.
create or replace function public.get_my_clinicians()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_patient();
  open_rows jsonb;
  last_ended jsonb;
begin
  select coalesce(jsonb_agg(jsonb_build_object(
      'assignment_id', a.id,
      'clinician_id', a.clinician_id,
      'clinician_name', private.display_name(a.clinician_id),
      'clinician_email_hint', private.mask_email(p.email),
      'status', a.status,
      'patient_consented_at', a.patient_consented_at,
      'clinician_approved_at', a.clinician_approved_at
    ) order by a.created_at desc), '[]'::jsonb)
  into open_rows
  from public.patient_clinician_assignments a
  join public.profiles p on p.id = a.clinician_id
  where a.patient_id = uid and a.status in ('active', 'pending');

  select jsonb_build_object(
      'assignment_id', a.id,
      'clinician_id', a.clinician_id,
      'clinician_name', private.display_name(a.clinician_id),
      'status', a.status,
      'revoked_at', a.revoked_at,
      'revoked_by_role', a.revoked_by_role
    )
  into last_ended
  from public.patient_clinician_assignments a
  where a.patient_id = uid and a.status in ('revoked', 'rejected')
  order by a.revoked_at desc
  limit 1;

  return jsonb_build_object('connections', open_rows, 'last_ended', last_ended);
end;
$$;

-- removeClinician(clinicianId): patient ends an active relationship or withdraws a pending one.
-- result: removed | not_found
create or replace function public.remove_clinician(p_clinician_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_patient();
  n int;
begin
  perform 1 from public.profiles where id = uid for update;
  update public.patient_clinician_assignments
  set status = case when status = 'active' then 'revoked' else 'cancelled' end,
      revoked_at = now(), revoked_by = uid, revoked_by_role = 'patient', updated_at = now()
  where patient_id = uid and clinician_id = p_clinician_id and status in ('active', 'pending');
  get diagnostics n = row_count;
  return jsonb_build_object('result', case when n > 0 then 'removed' else 'not_found' end);
end;
$$;

-- getMyAssignedPatients(): active patients of the calling verified clinician.
create or replace function public.get_my_assigned_patients()
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
        'assignment_id', a.id,
        'patient_id', a.patient_id,
        'patient_name', private.display_name(a.patient_id),
        'patient_email', p.email,
        'connected_since', coalesce(a.clinician_approved_at, a.patient_consented_at)
      ) order by private.display_name(a.patient_id)), '[]'::jsonb)
    from public.patient_clinician_assignments a
    join public.profiles p on p.id = a.patient_id
    where a.clinician_id = uid and a.status = 'active'
  );
end;
$$;

-- Pending reconnection requests addressed to the calling verified clinician.
create or replace function public.get_pending_reconnection_requests()
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
        'request_id', a.id,
        'patient_id', a.patient_id,
        'patient_name', private.display_name(a.patient_id),
        'patient_email', p.email,
        'status', a.status,
        'requested_at', a.patient_consented_at
      ) order by a.patient_consented_at), '[]'::jsonb)
    from public.patient_clinician_assignments a
    join public.profiles p on p.id = a.patient_id
    where a.clinician_id = uid and a.status = 'pending'
  );
end;
$$;

-- Patient details for the clinician's progress screen. Re-checked on every open, so a stale
-- navigation route to a removed patient returns no_access.
-- result: ok | no_access
create or replace function public.get_patient_overview(p_patient_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_verified_clinician();
  a public.patient_clinician_assignments;
  p public.profiles;
begin
  select * into a from public.patient_clinician_assignments
  where clinician_id = uid and patient_id = p_patient_id and status = 'active';
  if not found then
    return jsonb_build_object('result', 'no_access');
  end if;
  select * into p from public.profiles where id = p_patient_id;
  return jsonb_build_object(
    'result', 'ok',
    'patient_id', p.id,
    'patient_name', private.display_name(p.id),
    'patient_email', p.email,
    'connected_since', coalesce(a.clinician_approved_at, a.patient_consented_at)
  );
end;
$$;

-- removePatient(patientId): clinician ends an active relationship. Other patients and the
-- clinician's code are unaffected; the patient's data is not touched.
-- result: removed | not_found
create or replace function public.remove_patient(p_patient_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_clinician();
  n int;
begin
  perform 1 from public.profiles where id = p_patient_id for update;
  update public.patient_clinician_assignments
  set status = 'revoked', revoked_at = now(), revoked_by = uid, revoked_by_role = 'clinician', updated_at = now()
  where clinician_id = uid and patient_id = p_patient_id and status = 'active';
  get diagnostics n = row_count;
  return jsonb_build_object('result', case when n > 0 then 'removed' else 'not_found' end);
end;
$$;

-- approveReconnection(requestId)
-- result: approved | not_found | patient_has_active_clinician
create or replace function public.approve_reconnection(p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_verified_clinician();
  pid uuid;
  req public.patient_clinician_assignments;
begin
  select patient_id into pid from public.patient_clinician_assignments
  where id = p_request_id and clinician_id = uid;
  if pid is null then
    return jsonb_build_object('result', 'not_found');
  end if;
  perform 1 from public.profiles where id = pid for update; -- same lock order as patient calls

  select * into req from public.patient_clinician_assignments
  where id = p_request_id and clinician_id = uid and status = 'pending'
  for update;
  if not found or not exists (select 1 from public.profiles where id = pid and role = 'patient') then
    return jsonb_build_object('result', 'not_found');
  end if;
  if exists (select 1 from public.patient_clinician_assignments
             where patient_id = pid and status = 'active') then
    return jsonb_build_object('result', 'patient_has_active_clinician');
  end if;

  update public.patient_clinician_assignments
  set status = 'active', clinician_approved_at = now(), updated_at = now()
  where id = req.id;
  return jsonb_build_object('result', 'approved');
end;
$$;

-- rejectReconnection(requestId)
-- result: rejected | not_found
create or replace function public.reject_reconnection(p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := private.require_verified_clinician();
  n int;
begin
  update public.patient_clinician_assignments
  set status = 'rejected', revoked_at = now(), revoked_by = uid, revoked_by_role = 'clinician', updated_at = now()
  where id = p_request_id and clinician_id = uid and status = 'pending';
  get diagnostics n = row_count;
  return jsonb_build_object('result', case when n > 0 then 'rejected' else 'not_found' end);
end;
$$;

-------------------------------------------------------------------------------
-- 6. Function privileges: signed-in users only. Internal helpers stay ungranted.
-------------------------------------------------------------------------------
do $$
declare
  fn text;
begin
  foreach fn in array array[
    'public.get_my_account()',
    'public.set_my_full_name(text)',
    'public.get_my_clinician_code()',
    'public.generate_clinician_code()',
    'public.regenerate_clinician_code()',
    'public.lookup_clinician_code(text)',
    'public.confirm_clinician_connection(text, boolean)',
    'public.get_my_clinicians()',
    'public.remove_clinician(uuid)',
    'public.get_my_assigned_patients()',
    'public.get_pending_reconnection_requests()',
    'public.get_patient_overview(uuid)',
    'public.remove_patient(uuid)',
    'public.approve_reconnection(uuid)',
    'public.reject_reconnection(uuid)'
  ] loop
    execute format('revoke all on function %s from public, anon', fn);
    execute format('grant execute on function %s to authenticated', fn);
  end loop;

  foreach fn in array array[
    'private.require_user()',
    'private.require_verified_clinician()',
    'private.require_clinician()',
    'private.require_patient()',
    'private.new_invite_code()',
    'private.normalize_code(text)',
    'private.mask_email(text)',
    'private.set_invite_code(uuid, boolean)',
    'private.pairing_rate_limited(uuid)',
    'private.log_pairing_attempt(uuid, boolean)',
    'private.clinician_for_code(text)',
    'private.connection_kind(uuid, uuid)',
    'private.display_name(uuid)'
  ] loop
    execute format('revoke all on function %s from public, anon, authenticated', fn);
  end loop;
end $$;

-- The trigger function only runs from the auth.users trigger.
revoke all on function public.handle_new_user() from public, anon, authenticated;
