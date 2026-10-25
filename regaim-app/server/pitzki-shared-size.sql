-- Pitzki's shared folder: videos up to 50 MB (the lip-synced singing video is bigger than the old 25 MB limit).
-- 50 MB is the most Supabase's free plan allows for one file. Safe to run again.
update storage.buckets set file_size_limit = 52428800 where id = 'pitzki-shared';
