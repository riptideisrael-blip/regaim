-- How long technical records are kept (the privacy policy says so): crash reports 90 days, the AI cost log 12 months.
-- Each insert removes what's past its time, so no scheduler is needed. Additive and safe to run again.
-- Supabase: SQL Editor > New query > paste all > Run.

create or replace function public.trim_crash_reports() returns trigger language plpgsql security definer set search_path = public as $$
begin
  delete from public.crash_reports where created_at < now() - interval '90 days';
  return null;
end $$;
drop trigger if exists crash_reports_trim on public.crash_reports;
create trigger crash_reports_trim after insert on public.crash_reports for each statement execute function public.trim_crash_reports();

create or replace function public.trim_ai_costs() returns trigger language plpgsql security definer set search_path = public as $$
begin
  delete from public.ai_costs where at < now() - interval '12 months';
  return null;
end $$;
drop trigger if exists ai_costs_trim on public.ai_costs;
create trigger ai_costs_trim after insert on public.ai_costs for each statement execute function public.trim_ai_costs();

-- Once now, for what's already past its time.
delete from public.crash_reports where created_at < now() - interval '90 days';
delete from public.ai_costs where at < now() - interval '12 months';
