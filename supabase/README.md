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
- Promote a clinician in the SQL Editor: `update public.profiles set role = 'clinician' where email = 'doctor@example.com';`

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
Recording upload and synchronization are deferred; their earlier implementation is documented
in [the integration guide](../ANDROID_SUPABASE_INTEGRATION.md).

## Secrets rules

- Never commit the `service_role` / secret key or the database password. The app only uses the anon/publishable key; RLS is the protection.
- Share credentials through a team password manager, not the repo or chat.
