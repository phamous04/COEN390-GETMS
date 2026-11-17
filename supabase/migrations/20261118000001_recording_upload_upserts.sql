-- Add idempotent recording uploads to an existing project without rerunning setup.sql,
-- which would replace the clinician-aware handle_new_user function from earlier migrations.
-- Safe to rerun; owner-only writes and assigned-clinician read access are preserved.
begin;

drop policy if exists "recordings_update_own" on storage.objects;
create policy "recordings_update_own" on storage.objects
  for update to authenticated
  using (bucket_id = 'recordings' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'recordings' and (storage.foldername(name))[1] = auth.uid()::text);

commit;
