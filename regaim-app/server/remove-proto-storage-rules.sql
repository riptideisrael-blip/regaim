-- Removes 3 leftover prototype rules on family media (storage, bucket 'moments'). Run only after you approved it.
-- They are already neutralized by the v3 "fence" rules, so nothing in the app changes; this removes a backup risk
-- (if a fence is ever removed by mistake, these would open family media to anyone).
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again.
drop policy if exists proto_read on storage.objects;
drop policy if exists proto_upload on storage.objects;
drop policy if exists proto_delete on storage.objects;

-- BACKUP / UNDO: to put them back exactly as they were, run these 3 lines instead (not needed normally):
-- create policy proto_read on storage.objects for select to anon, authenticated using (bucket_id = 'moments');
-- create policy proto_upload on storage.objects for insert to anon, authenticated with check (bucket_id = 'moments');
-- create policy proto_delete on storage.objects for delete to anon, authenticated using (bucket_id = 'moments');
