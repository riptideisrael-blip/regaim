-- What the drawing service (fal) keeps of our requests, and deleting it.
-- fal_jobs already says whose request it is; this adds: what kind it was, whether fal keeps its record until we
-- delete it ("stored"), and when we deleted it there. A row now outlives its account (without the account's id),
-- so a request can still be deleted at fal after the account is gone.
-- Safe to run more than once. Run once in Supabase: SQL Editor -> New query -> paste -> Run.
alter table public.fal_jobs add column if not exists kind text;
alter table public.fal_jobs add column if not exists stored boolean not null default true;
alter table public.fal_jobs add column if not exists deleted_at timestamptz;
alter table public.fal_jobs add column if not exists tries int not null default 0;

alter table public.fal_jobs alter column user_id drop not null;
alter table public.fal_jobs drop constraint if exists fal_jobs_user_id_fkey;
alter table public.fal_jobs add constraint fal_jobs_user_id_fkey
  foreign key (user_id) references auth.users(id) on delete set null;

create index if not exists fal_jobs_waiting on public.fal_jobs(created_at) where stored and deleted_at is null;

-- Still only the server reads and writes it.
alter table public.fal_jobs enable row level security;
revoke all on public.fal_jobs from anon, authenticated;
