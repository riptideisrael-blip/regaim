-- Backup taken 2026-10-25 before running money-guards.sql (to undo: run this file).
-- film_settings had no tts_* rows (the server function's default was 150 lines a day).
-- Undo also: delete from public.film_settings where key in ('tts_daily_limit','tts_daily_chars');
--            drop function if exists public.tts_use_chars(uuid, int, int, int);
-- (The new columns film_credits.last_refund_at and tts_usage.chars can stay; nothing old reads them.)

CREATE OR REPLACE FUNCTION public.film_refund(p_user uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$ begin update film_credits set balance = balance + 1, active_until = null, updated_at = now() where user_id = p_user and active_until > now() and calls <= 3; return found; end $function$;

CREATE OR REPLACE FUNCTION public.film_refund_once(p_user uuid, p_ref text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$ begin update film_credits set balance = balance + 1, active_until = null, active_ref = null, updated_at = now() where user_id = p_user and active_ref = p_ref and calls <= 5 and active_until > now() - interval '24 hours'; return found; end $function$;

revoke all on function public.film_refund_once(uuid, text), public.film_refund(uuid) from public, anon, authenticated;
grant execute on function public.film_refund_once(uuid, text), public.film_refund(uuid) to service_role;
