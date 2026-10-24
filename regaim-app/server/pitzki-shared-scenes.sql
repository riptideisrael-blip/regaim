-- Pitzki's shared close-ups (Pitzki alone with each color, the same for every family).
-- Everyone can watch them (a public folder); only the admin can put them there.
-- Nothing else changes: family photos and videos ("moments") keep all their rules.
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('pitzki-shared', 'pitzki-shared', true, 26214400, array['image/jpeg', 'video/mp4', 'audio/mpeg'])
on conflict (id) do update set allowed_mime_types = array['image/jpeg', 'video/mp4', 'audio/mpeg'];

drop policy if exists pitzki_shared_admin_insert on storage.objects;
create policy pitzki_shared_admin_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'pitzki-shared' and lower(auth.jwt() ->> 'email') = 'yossefrevivo@gmail.com');

-- Replacing a close-up (made again) needs these two as well.
drop policy if exists pitzki_shared_admin_update on storage.objects;
create policy pitzki_shared_admin_update on storage.objects for update to authenticated
  using (bucket_id = 'pitzki-shared' and lower(auth.jwt() ->> 'email') = 'yossefrevivo@gmail.com')
  with check (bucket_id = 'pitzki-shared' and lower(auth.jwt() ->> 'email') = 'yossefrevivo@gmail.com');
drop policy if exists pitzki_shared_admin_select on storage.objects;
create policy pitzki_shared_admin_select on storage.objects for select to authenticated
  using (bucket_id = 'pitzki-shared' and lower(auth.jwt() ->> 'email') = 'yossefrevivo@gmail.com');

-- UNDO (not needed normally): run these 3 lines, then delete the 'pitzki-shared' bucket in Storage.
-- drop policy if exists pitzki_shared_admin_insert on storage.objects;
-- drop policy if exists pitzki_shared_admin_update on storage.objects;
-- drop policy if exists pitzki_shared_admin_select on storage.objects;
