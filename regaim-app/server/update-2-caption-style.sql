alter table posts add column if not exists caption text;
alter table posts add column if not exists style text default 'plain';
alter table posts add column if not exists memory_id text;
