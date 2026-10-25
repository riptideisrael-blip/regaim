-- Moments · Family Circle prototype (for ~10 people)
-- Paste everything into Supabase → SQL Editor → Run. Running it twice is safe.
-- PROTOTYPE SECURITY: anyone who has the circle link can see and add. Fine for a demo, not for real customers.

create table if not exists circles (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  name text not null,
  created_at timestamptz default now()
);

create table if not exists posts (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid references circles(id) on delete cascade,
  author text,
  title text,
  story text,
  people text,
  place text,
  kind text default 'video',
  taken_at timestamptz,
  video text,
  cover text,
  created_at timestamptz default now()
);

create table if not exists reactions (
  id uuid primary key default gen_random_uuid(),
  post_id uuid references posts(id) on delete cascade,
  member text not null,
  emoji text not null,
  created_at timestamptz default now(),
  unique (post_id, member, emoji)
);

create table if not exists comments (
  id uuid primary key default gen_random_uuid(),
  post_id uuid references posts(id) on delete cascade,
  member text not null,
  body text not null,
  created_at timestamptz default now()
);

alter table circles enable row level security;
alter table posts enable row level security;
alter table reactions enable row level security;
alter table comments enable row level security;

drop policy if exists "proto circles" on circles;
drop policy if exists "proto posts" on posts;
drop policy if exists "proto reactions" on reactions;
drop policy if exists "proto comments" on comments;
create policy "proto circles" on circles for all to anon, authenticated using (true) with check (true);
create policy "proto posts" on posts for all to anon, authenticated using (true) with check (true);
create policy "proto reactions" on reactions for all to anon, authenticated using (true) with check (true);
create policy "proto comments" on comments for all to anon, authenticated using (true) with check (true);

-- Storage for clips and covers
insert into storage.buckets (id, name, public)
values ('moments', 'moments', true)
on conflict (id) do nothing;

drop policy if exists "proto moments read" on storage.objects;
drop policy if exists "proto moments upload" on storage.objects;
drop policy if exists "proto moments delete" on storage.objects;
create policy "proto moments read" on storage.objects for select to anon, authenticated using (bucket_id = 'moments');
create policy "proto moments upload" on storage.objects for insert to anon, authenticated with check (bucket_id = 'moments');
create policy "proto moments delete" on storage.objects for delete to anon, authenticated using (bucket_id = 'moments');
