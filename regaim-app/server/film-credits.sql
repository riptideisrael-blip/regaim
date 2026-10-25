-- Cartoon-film credits. Run once in Supabase: SQL Editor -> New query -> paste -> Run.
create table if not exists public.film_credits (
  user_id uuid primary key references auth.users(id) on delete cascade,
  balance int not null default 3,
  active_until timestamptz,
  calls int not null default 0,
  updated_at timestamptz not null default now()
);
alter table public.film_credits enable row level security;
drop policy if exists "film credits: read own" on public.film_credits;
create policy "film credits: read own" on public.film_credits for select using (auth.uid() = user_id);

create or replace function public.film_balance(p_user uuid) returns int
language plpgsql security definer set search_path = public as $$
begin
  insert into film_credits(user_id) values (p_user) on conflict do nothing;
  return (select balance from film_credits where user_id = p_user);
end $$;

-- Takes one credit and opens a 30-minute window of up to 12 AI calls.
create or replace function public.film_reserve(p_user uuid) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  insert into film_credits(user_id) values (p_user) on conflict do nothing;
  update film_credits set balance = balance - 1, active_until = now() + interval '30 minutes', calls = 0, updated_at = now()
   where user_id = p_user and balance > 0;
  return found;
end $$;

create or replace function public.film_use_call(p_user uuid) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  update film_credits set calls = calls + 1
   where user_id = p_user and active_until > now() and calls < 20;
  return found;
end $$;

create or replace function public.film_refund(p_user uuid) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  -- Refund only if almost nothing was spent (a few drawings at most), so reserve → spend → refund can't loop for free.
  update film_credits set balance = balance + 1, active_until = null, updated_at = now()
   where user_id = p_user and active_until > now() and calls <= 3;
  return found;
end $$;

revoke all on function public.film_balance(uuid), public.film_reserve(uuid), public.film_use_call(uuid), public.film_refund(uuid) from public, anon, authenticated;
grant execute on function public.film_balance(uuid), public.film_reserve(uuid), public.film_use_call(uuid), public.film_refund(uuid) to service_role;

-- To give someone more credits (replace the email and the number):
-- update public.film_credits set balance = balance + 10 where user_id = (select id from auth.users where email = 'you@example.com');
