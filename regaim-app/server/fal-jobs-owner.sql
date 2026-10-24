-- Whose fal job is it: the server writes a row when it sends a request, so only that account can ask about it
-- (poll) or delete it (forget). Safe to run more than once; changes nothing that exists.
-- Run once in Supabase: SQL Editor -> New query -> paste -> Run.
create table if not exists public.fal_jobs (
  request_id text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);
alter table public.fal_jobs enable row level security;
-- No policies: only the server (service role) reads and writes it.
revoke all on public.fal_jobs from anon, authenticated;
create index if not exists fal_jobs_created on public.fal_jobs(created_at);
