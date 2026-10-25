-- Money guards (Oct 24). Adds two columns and replaces three server functions; changes no existing data.
-- Safe to run again. Supabase: SQL Editor > New query > paste all > Run.
--
-- 1) A film credit can be given back at most once in 24 hours per account. Before this, a changed app could
--    reserve a credit, spend a few paid calls, ask for it back, and repeat without end.
-- 2) Pitzki's voice (ElevenLabs) is limited by characters per day per account (not only by lines), so one account
--    can't use up the month's voice for everyone.

-- 1) ---------------------------------------------------------------------------------------------------------
alter table public.film_credits add column if not exists last_refund_at timestamptz;

create or replace function public.film_refund_once(p_user uuid, p_ref text) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  update film_credits
     set balance = balance + 1, active_until = null, active_ref = null, last_refund_at = now(), updated_at = now()
   where user_id = p_user and active_ref = p_ref and calls <= 5 and active_until > now() - interval '24 hours'
     and (last_refund_at is null or last_refund_at < now() - interval '24 hours');
  return found;
end $$;

-- The older path (still on the server for old app versions): the same once-a-day rule.
create or replace function public.film_refund(p_user uuid) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  update film_credits
     set balance = balance + 1, active_until = null, last_refund_at = now(), updated_at = now()
   where user_id = p_user and active_until > now() and calls <= 3
     and (last_refund_at is null or last_refund_at < now() - interval '24 hours');
  return found;
end $$;

revoke all on function public.film_refund_once(uuid, text), public.film_refund(uuid) from public, anon, authenticated;
grant execute on function public.film_refund_once(uuid, text), public.film_refund(uuid) to service_role;

-- 2) ---------------------------------------------------------------------------------------------------------
alter table public.tts_usage add column if not exists chars int not null default 0;

-- Counts one more line of [p_chars] characters; false when the day's lines or characters would go over the limit
-- (then nothing is counted, so a refused line doesn't use up the day).
create or replace function public.tts_use_chars(p_user uuid, p_chars int, p_line_limit int, p_char_limit int) returns boolean
language plpgsql security definer set search_path = '' as $$
declare cur_n int; cur_chars int;
begin
  insert into public.tts_usage(user_id, day, n, chars) values (p_user, current_date, 0, 0)
  on conflict (user_id, day) do nothing;
  select n, chars into cur_n, cur_chars from public.tts_usage where user_id = p_user and day = current_date for update;
  if cur_n + 1 > p_line_limit or cur_chars + greatest(p_chars, 0) > p_char_limit then return false; end if;
  update public.tts_usage set n = n + 1, chars = chars + greatest(p_chars, 0) where user_id = p_user and day = current_date;
  return true;
end $$;
revoke all on function public.tts_use_chars(uuid, int, int, int) from public, anon, authenticated;
grant execute on function public.tts_use_chars(uuid, int, int, int) to service_role;

-- The limits (change here any time): lines and characters per account per day.
insert into public.film_settings (key, value) values ('tts_daily_limit', '150'), ('tts_daily_chars', '5000')
on conflict (key) do update set value = excluded.value, updated_at = now();

-- Check: both rows should say true.
select 'refund once a day' as what, exists (select 1 from information_schema.columns where table_name = 'film_credits' and column_name = 'last_refund_at') as ok
union all
select 'voice by characters', exists (select 1 from pg_proc where proname = 'tts_use_chars');
