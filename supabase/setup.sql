-- US 0.2: baseline Supabase backend for GETMS (development project).
-- Run once in Dashboard > SQL Editor. Safe to re-run.
-- Full schema (recordings, metrics, ...) comes later in story 7.1.

-- 1. Profiles: one row per auth user, with a role used by later RLS policies.
create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  email text,
  full_name text,
  role text not null default 'patient' check (role in ('patient', 'clinician')),
  created_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own" on public.profiles
  for select to authenticated using (id = auth.uid());

-- Users may edit their own name but never their role (column-level grant).
drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
  for update to authenticated using (id = auth.uid()) with check (id = auth.uid());
revoke update on public.profiles from authenticated;
grant update (full_name) on public.profiles to authenticated;

-- Always sign up as 'patient'; clinicians are promoted manually by an admin.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, email, full_name)
  values (new.id, new.email, new.raw_user_meta_data ->> 'full_name');
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- 2. Private Storage bucket for raw recordings.
insert into storage.buckets (id, name, public, file_size_limit)
values ('recordings', 'recordings', false, 52428800) -- 50 MB
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit;

-- Object path convention: <user_id>/<file>. Users only touch their own folder.
drop policy if exists "recordings_select_own" on storage.objects;
create policy "recordings_select_own" on storage.objects
  for select to authenticated
  using (bucket_id = 'recordings' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "recordings_insert_own" on storage.objects;
create policy "recordings_insert_own" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'recordings' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "recordings_delete_own" on storage.objects;
create policy "recordings_delete_own" on storage.objects
  for delete to authenticated
  using (bucket_id = 'recordings' and (storage.foldername(name))[1] = auth.uid()::text);
