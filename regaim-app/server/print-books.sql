-- Printed books (Lulu): a private storage place for the print files (the book's pages and its cover, as PDF).
-- Each account can add files only in its own folder; Lulu gets a time-limited link made by the server.
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again. One statement per line.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values ('print', 'print', false, 209715200, array['application/pdf']) on conflict (id) do update set public = false, file_size_limit = 209715200, allowed_mime_types = array['application/pdf'];
drop policy if exists print_insert on storage.objects;
create policy print_insert on storage.objects for insert to authenticated with check (bucket_id = 'print' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists print_read on storage.objects;
create policy print_read on storage.objects for select to authenticated using (bucket_id = 'print' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists print_update on storage.objects;
create policy print_update on storage.objects for update to authenticated using (bucket_id = 'print' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists print_delete on storage.objects;
create policy print_delete on storage.objects for delete to authenticated using (bucket_id = 'print' and (storage.foldername(name))[1] = auth.uid()::text);
