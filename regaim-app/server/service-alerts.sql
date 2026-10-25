-- Which paid service ran out (pilot): the server writes a line when a service says "no balance / quota / key",
-- and marks it fixed when that service works again. Only the server reads and writes it; the admin screen shows it.
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again.
create table if not exists public.service_alerts (
  service text primary key,          -- fal, elevenlabs, anthropic, gemini
  status int not null default 0,     -- the service's own error code
  message text not null default '',  -- what the service said
  at timestamptz not null default now(),
  ok_at timestamptz                  -- when it worked again (empty = still out)
);
alter table public.service_alerts enable row level security;
-- No policies: only the server (service role) can read or write it.

-- UNDO (not needed normally): drop table if exists public.service_alerts;
