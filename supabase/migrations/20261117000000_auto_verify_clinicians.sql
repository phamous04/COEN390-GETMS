-- ReBand: clinician accounts are verified automatically at sign-up (no administrator approval).
--
-- Apply after 20261116000000_clinician_patient_pairing.sql. Safe to re-run.
--
-- Patient data stays protected by patient consent: a clinician still sees nothing until a patient
-- enters their code and explicitly confirms, and the patient can remove them at any time.
-- An administrator can still revoke a clinician:
--   update public.profiles set clinician_verified_at = null where email = '...';

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  is_clinician boolean := new.raw_user_meta_data ->> 'account_type' = 'clinician';
begin
  insert into public.profiles (id, email, full_name, role, clinician_verified_at)
  values (
    new.id,
    new.email,
    left(nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''), 80),
    case when is_clinician then 'clinician' else 'patient' end,
    case when is_clinician then now() end
  );
  return new;
end;
$$;

revoke all on function public.handle_new_user() from public, anon, authenticated;

-- Clinicians who signed up while approval was still required become verified now.
-- (If you had deliberately revoked someone, set their clinician_verified_at back to null after this.)
update public.profiles
set clinician_verified_at = now()
where role = 'clinician' and clinician_verified_at is null;
