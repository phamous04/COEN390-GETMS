# Supabase setup (US 0.2)

Shared **development** project: Auth + PostgreSQL + private Storage.

## 1. Create the project (one team member, once)

1. Sign in at <https://supabase.com/dashboard> (GitHub login is fine) and create an organization, e.g. `COEN390-GETMS`.
2. **New project**: name `getms-dev`, generate and save a strong database password in the team password manager, pick the region closest to the team, Free plan.
3. Wait for provisioning to finish.

## 2. Give the team access

Dashboard > Organization settings > **Team** > invite each teammate by email (role **Developer**; **Owner** only for the project lead). This satisfies "accessible to authorized team members".

## 3. Authentication

Dashboard > **Authentication**:

- **Sign In / Providers**: keep **Email** enabled; disable providers you don't use.
- Minimum password length: 8 or more.
- **Confirm email**: turn OFF for development to ease testing (ON for any real demo).
- **URL Configuration**: leave the defaults for now (deep links are added in the login story, 4.1).

## 4. Database and Storage

Dashboard > **SQL Editor** > New query > paste [setup.sql](./setup.sql) > **Run**. It creates:

- `public.profiles` (auth user + role `patient`/`clinician`), RLS enabled, auto-filled on signup by a trigger. Users cannot change their own role.
- Private Storage bucket **`recordings`** (not public, 50 MB limit). Files must be uploaded under `<user_id>/...`; RLS lets users read, write and delete only their own folder.

Verify:

- Table Editor shows `profiles` with RLS enabled.
- Storage shows `recordings` marked **Private**.
- Authentication > Users > **Add user** creates a test user and a matching `profiles` row appears.
- Clinician accounts: see section 6.

## 5. Connect the Android app

Dashboard > **Project Settings > API** (or **Connect**): copy the **Project URL** and the **anon / publishable key**.

Put them in `Android_app/local.properties` (git-ignored, never commit):

```
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=<publishable-key>
```

The Android build reads these values from `local.properties` and uses them for email/password
authentication. Rebuild the app after changing them. In Profile, users can create an account,
sign in, and sign out; the client restores saved sessions. If email confirmation is enabled,
confirm the address before signing in. The app does not handle confirmation deep links.
Recording upload and synchronization are deferred to a later story.

## 6. Clinician–patient pairing

In Dashboard > **SQL Editor**, run these two files in order. Both only add things and are safe to re-run:

1. [migrations/20261116000000_clinician_patient_pairing.sql](./migrations/20261116000000_clinician_patient_pairing.sql)
2. [migrations/20261117000000_auto_verify_clinicians.sql](./migrations/20261117000000_auto_verify_clinicians.sql)

They add:

- `clinician_invite_codes`: one reusable code per clinician. Regenerating replaces it.
- `patient_clinician_assignments`: relationship history with the statuses `pending`, `active`, `revoked`,
  `rejected` and `cancelled`. Rows are never deleted when someone disconnects.
- `profiles.clinician_verified_at`: set automatically when someone signs up as "Clinician".
- Schema `private`: helper functions and the code-guessing rate-limit log. The REST API does not expose it.
- RPC functions used by the app (`get_my_account`, `generate_clinician_code`, `lookup_clinician_code`,
  `confirm_clinician_connection`, `remove_patient`, ...). Every write goes through them.
- RLS: users can only read their own rows.
- Storage policy `recordings_select_assigned_clinician`: a clinician with an **active** assignment can read, but not
  write or delete, `recordings/<patient_id>/...`.

**Clinician accounts.** Choosing "Clinician" at sign-up creates a clinician account right away, with no
administrator step. Patient data stays protected by consent: a clinician sees a patient only after that patient
enters the clinician's code and confirms, and the patient can remove them at any time. An administrator can still
turn a clinician off. Their access and code stop working immediately:

```sql
update public.profiles set clinician_verified_at = null where email = 'doctor@example.com';
```

Set it back to `now()` to turn them on again.

**Signed URLs.** Revoking access blocks new reads immediately, but a signed URL that was already issued stays valid
until it expires. When recording download is built, create signed URLs only at the moment of viewing, with
`expiresIn` of 60 seconds or less, and never store them.

## Secrets rules

- Never commit the `service_role` / secret key or the database password. The app only uses the anon/publishable key; RLS is the protection.
- Share credentials through a team password manager, not the repo or chat.
