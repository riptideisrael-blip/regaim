-- Community media becomes private: photos and videos open only for signed-in members, through short-lived
-- signed links, and only for posts they may see (not hidden, author not blocked). Run once, after community-v4.sql.
-- Every statement is one line on purpose (the Supabase editor adds brackets to multi-line pastes on phones).
update storage.buckets set public = false where id = 'community';
drop policy if exists community_media_read on storage.objects;
create policy community_media_read on storage.objects for select to authenticated using (bucket_id = 'community' and ((storage.foldername(name))[1] = auth.uid()::text or public.community_is_admin() or exists (select 1 from public.community_posts p where p.media_path = storage.objects.name or p.cover_path = storage.objects.name)));
