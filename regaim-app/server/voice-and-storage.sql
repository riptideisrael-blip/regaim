-- Two server additions (Oct 23). Adds tables/functions and one setting; changes no existing data. Safe to run again.
-- Supabase: SQL Editor > New query > paste all > Run.
-- 1+2) Pitzki's real voice (ElevenLabs) for every family, with a daily limit per account. 3) The admin's storage meter.

-- 1) How many voice lines each account made today (only the server reads and writes it).
create table if not exists public.tts_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  day date not null default current_date,
  n int not null default 0,
  primary key (user_id, day)
);
alter table public.tts_usage enable row level security;
-- No policies: only the server (service role) can touch it.

-- Counts one more line for the account; false when the day's limit is reached.
create or replace function public.tts_use(p_user uuid, p_limit int) returns boolean
language plpgsql security definer set search_path = '' as $$
declare used int;
begin
  insert into public.tts_usage(user_id, day, n) values (p_user, current_date, 1)
  on conflict (user_id, day) do update set n = public.tts_usage.n + 1
  returning n into used;
  return used <= p_limit;
end $$;
revoke all on function public.tts_use(uuid, int) from public, anon, authenticated;
grant execute on function public.tts_use(uuid, int) to service_role;

-- 2) Open the voice to everyone (it was the admin only while testing).
insert into public.film_settings (key, value) values ('pitzki_voice_access', 'everyone')
on conflict (key) do update set value = excluded.value, updated_at = now();

-- UNDO (back to the admin only):
--   update public.film_settings set value = 'admin', updated_at = now() where key = 'pitzki_voice_access';

-- 3) The admin screen: how much room the server's files take (the free plan has 1 GB). Only the admin can read it.
create or replace function public.admin_storage() returns jsonb
language plpgsql security definer
set search_path = public, auth, storage
as $$
begin
  if not exists (
    select 1 from auth.users u
    where u.id = auth.uid() and lower(u.email) in ('yossefrevivo@gmail.com') and u.email_confirmed_at is not null
  ) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'total', (select coalesce(sum((metadata->>'size')::bigint), 0) from storage.objects),
    'buckets', (select coalesce(jsonb_agg(b order by (b->>'bytes')::bigint desc), '[]'::jsonb) from (
      select jsonb_build_object('id', bucket_id, 'bytes', coalesce(sum((metadata->>'size')::bigint), 0), 'files', count(*)) as b
      from storage.objects group by bucket_id) x));
end $$;
revoke all on function public.admin_storage() from public, anon;
grant execute on function public.admin_storage() to authenticated;
