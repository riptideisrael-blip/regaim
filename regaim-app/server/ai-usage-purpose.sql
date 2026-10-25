-- Where the AI allowance goes: calls and pictures per family, per month, per kind (describe, ask, book, day_story,
-- learn, comic, pitzki_draw...). Only counters: no content. One statement per line (phone editor). Safe to run again.
create table if not exists public.ai_usage_purpose (budget_key text not null, month text not null, purpose text not null, calls int not null default 0, images int not null default 0, primary key (budget_key, month, purpose));
alter table public.ai_usage_purpose enable row level security;
create or replace function public.ai_count_purpose(p_user uuid, p_purpose text, p_images int) returns void language plpgsql security definer set search_path = public as $$ declare k text := public.ai_budget_key(p_user); m text := to_char(now(), 'YYYY-MM'); begin insert into ai_usage_purpose(budget_key, month, purpose, calls, images) values (k, m, left(coalesce(nullif(p_purpose, ''), 'other'), 40), 1, greatest(coalesce(p_images, 0), 0)) on conflict (budget_key, month, purpose) do update set calls = ai_usage_purpose.calls + 1, images = ai_usage_purpose.images + excluded.images; end $$;
revoke all on function public.ai_count_purpose(uuid, text, int) from public, anon, authenticated;
grant execute on function public.ai_count_purpose(uuid, text, int) to service_role;

-- To see this month (run on its own):
-- select purpose, calls, images from public.ai_usage_purpose where month = to_char(now(), 'YYYY-MM') order by calls desc;
