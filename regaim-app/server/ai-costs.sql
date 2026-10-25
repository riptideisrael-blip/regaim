-- What each drawing/animation at fal cost (an estimate from the server's price list), for the admin's "costs" card.
-- Only the server writes and reads it. One statement per line (phone editor). Safe to run again.
create table if not exists public.ai_costs (id bigserial primary key, user_id uuid, kind text not null, model text not null, ref text, seconds int not null default 0, est_usd numeric(10,4) not null default 0, at timestamptz not null default now());
create index if not exists ai_costs_at on public.ai_costs (at desc);
create index if not exists ai_costs_ref on public.ai_costs (user_id, ref);
alter table public.ai_costs enable row level security;
