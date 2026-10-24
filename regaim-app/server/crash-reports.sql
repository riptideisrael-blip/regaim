-- Crash reports from the app (CrashLog.kt): what broke, on which screen, which version and phone.
-- Nothing personal is sent. Phones can only add a report; reading them is for the admin (the Supabase dashboard).
-- Safe to run more than once.

create table if not exists public.crash_reports (
  id bigserial primary key,
  created_at timestamptz not null default now(),
  user_id uuid default auth.uid(),
  app_version text check (length(app_version) <= 60),
  device text check (length(device) <= 120),
  android int,
  screen text check (length(screen) <= 120),
  thread text check (length(thread) <= 120),
  trace text check (length(trace) <= 10000)
);

alter table public.crash_reports enable row level security;

drop policy if exists "crash reports: phones add" on public.crash_reports;
create policy "crash reports: phones add" on public.crash_reports
  for insert to anon, authenticated
  with check (user_id is null or user_id = auth.uid());

-- No select / update / delete policies: phones can't read anyone's reports.
grant insert on public.crash_reports to anon, authenticated;
grant usage, select on sequence public.crash_reports_id_seq to anon, authenticated;
