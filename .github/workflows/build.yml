-- "Is there a new version?" in the app's settings: a PRIVATE folder for the newest APK.
-- Only someone signed in to the app can download from it. Only the GitHub build puts files there (with the
-- server's own key, which isn't subject to these rules), so no one can upload from a phone.
-- Adds a new folder only: nothing existing changes or is deleted. Safe to run again.
-- Supabase: SQL Editor > New query > paste all > Run.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('app-updates', 'app-updates', false, 52428800, array['application/vnd.android.package-archive', 'application/octet-stream', 'application/json'])
on conflict (id) do update set public = false, file_size_limit = 52428800,
  allowed_mime_types = array['application/vnd.android.package-archive', 'application/octet-stream', 'application/json'];

drop policy if exists app_updates_signed_in_read on storage.objects;
create policy app_updates_signed_in_read on storage.objects for select to authenticated
  using (bucket_id = 'app-updates');

-- UNDO (not needed normally): run this line, then delete the 'app-updates' bucket in Storage.
-- drop policy if exists app_updates_signed_in_read on storage.objects;
