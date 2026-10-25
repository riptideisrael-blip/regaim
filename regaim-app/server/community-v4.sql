-- Moments community feed (v4). Run AFTER secure-family-circle-v3.sql.
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again.
--
-- What it adds: a PUBLIC feed that only signed-in members of the app can read, where each post is
-- something its author deliberately chose to publish to "the community".
-- Family circles stay private and are not touched.

-- ---------- who is the admin (same email as admin-stats.sql) ----------
create or replace function public.community_is_admin() returns boolean
language sql stable security definer set search_path = public, auth as $$
  select exists (
    select 1 from auth.users u
    where u.id = auth.uid() and lower(u.email) in ('yossefrevivo@gmail.com') and u.email_confirmed_at is not null
  );
$$;

-- Guests (anonymous web visitors) can read nothing and write nothing here.
create or replace function public.community_is_member() returns boolean
language sql stable as $$
  select auth.uid() is not null and coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) = false;
$$;

-- ---------- tables ----------
create table if not exists public.community_posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  author_name text not null check (char_length(author_name) between 1 and 40),
  title text not null default '' check (char_length(title) <= 120),
  caption text not null default '' check (char_length(caption) <= 1000),
  kind text not null default 'photo' check (kind in ('photo', 'video')),
  media_path text not null,
  cover_path text not null default '',
  taken_at timestamptz,
  hidden boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists community_posts_created on public.community_posts (created_at desc);

create table if not exists public.community_likes (
  post_id uuid not null references public.community_posts(id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table if not exists public.community_comments (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.community_posts(id) on delete cascade,
  author_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  author_name text not null check (char_length(author_name) between 1 and 40),
  body text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now()
);
create index if not exists community_comments_post on public.community_comments (post_id, created_at);

create table if not exists public.community_reports (
  post_id uuid not null references public.community_posts(id) on delete cascade,
  reporter_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  reason text not null default '' check (char_length(reason) <= 60),
  created_at timestamptz not null default now(),
  primary key (post_id, reporter_id)
);

create table if not exists public.community_blocks (
  blocker_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);

-- ---------- 3 different people reporting a post hides it until the admin decides ----------
create or replace function public.community_auto_hide() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if (select count(*) from public.community_reports where post_id = new.post_id) >= 3 then
    update public.community_posts set hidden = true where id = new.post_id;
  end if;
  return new;
end;
$$;
drop trigger if exists community_auto_hide on public.community_reports;
create trigger community_auto_hide after insert on public.community_reports
  for each row execute function public.community_auto_hide();

-- ---------- row level security ----------
alter table public.community_posts enable row level security;
alter table public.community_likes enable row level security;
alter table public.community_comments enable row level security;
alter table public.community_reports enable row level security;
alter table public.community_blocks enable row level security;

drop policy if exists community_posts_read on public.community_posts;
create policy community_posts_read on public.community_posts for select to authenticated using (
  public.community_is_member() and (
    author_id = auth.uid()
    or public.community_is_admin()
    or (not hidden and not exists (
        select 1 from public.community_blocks b where b.blocker_id = auth.uid() and b.blocked_id = community_posts.author_id))
  )
);
drop policy if exists community_posts_insert on public.community_posts;
create policy community_posts_insert on public.community_posts for insert to authenticated
  with check (
    public.community_is_member() and author_id = auth.uid() and hidden = false
    -- files must live in the author's own folder (no pointing at other people's files, no ../)
    and media_path like author_id::text || '/%' and media_path not like '%..%' and char_length(media_path) <= 300
    and (cover_path = '' or (cover_path like author_id::text || '/%' and cover_path not like '%..%' and char_length(cover_path) <= 300))
  );
drop policy if exists community_posts_update on public.community_posts;
create policy community_posts_update on public.community_posts for update to authenticated
  using (public.community_is_admin()) with check (public.community_is_admin());
drop policy if exists community_posts_delete on public.community_posts;
create policy community_posts_delete on public.community_posts for delete to authenticated
  using (author_id = auth.uid() or public.community_is_admin());

drop policy if exists community_likes_read on public.community_likes;
create policy community_likes_read on public.community_likes for select to authenticated using (
  public.community_is_member()
  and exists (select 1 from public.community_posts p where p.id = community_likes.post_id)  -- only posts you may see
);
drop policy if exists community_likes_insert on public.community_likes;
create policy community_likes_insert on public.community_likes for insert to authenticated
  with check (public.community_is_member() and user_id = auth.uid()
    and exists (select 1 from public.community_posts p where p.id = community_likes.post_id));
drop policy if exists community_likes_delete on public.community_likes;
create policy community_likes_delete on public.community_likes for delete to authenticated using (user_id = auth.uid());

drop policy if exists community_comments_read on public.community_comments;
create policy community_comments_read on public.community_comments for select to authenticated using (
  public.community_is_member()
  and exists (select 1 from public.community_posts p where p.id = community_comments.post_id)  -- hidden posts stay hidden
  and not exists (select 1 from public.community_blocks b where b.blocker_id = auth.uid() and b.blocked_id = community_comments.author_id)
);
drop policy if exists community_comments_insert on public.community_comments;
create policy community_comments_insert on public.community_comments for insert to authenticated
  with check (public.community_is_member() and author_id = auth.uid()
    and exists (select 1 from public.community_posts p where p.id = community_comments.post_id));
drop policy if exists community_comments_delete on public.community_comments;
create policy community_comments_delete on public.community_comments for delete to authenticated using (
  author_id = auth.uid() or public.community_is_admin()
  or exists (select 1 from public.community_posts p where p.id = post_id and p.author_id = auth.uid())
);

drop policy if exists community_reports_insert on public.community_reports;
create policy community_reports_insert on public.community_reports for insert to authenticated
  with check (public.community_is_member() and reporter_id = auth.uid());
drop policy if exists community_reports_read on public.community_reports;
create policy community_reports_read on public.community_reports for select to authenticated using (public.community_is_admin());

drop policy if exists community_blocks_all on public.community_blocks;
create policy community_blocks_all on public.community_blocks for all to authenticated
  using (blocker_id = auth.uid()) with check (public.community_is_member() and blocker_id = auth.uid());

grant select, insert, delete on public.community_posts to authenticated;
grant update on public.community_posts to authenticated;
grant select, insert, delete on public.community_likes to authenticated;
grant select, insert, delete on public.community_comments to authenticated;
grant select, insert on public.community_reports to authenticated;
grant select, insert, delete on public.community_blocks to authenticated;
revoke all on public.community_posts, public.community_likes, public.community_comments,
  public.community_reports, public.community_blocks from anon;

-- ---------- media: a separate PUBLIC bucket; people can add only inside their own folder ----------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  values ('community', 'community', true, 52428800, array['image/jpeg', 'video/mp4'])
  on conflict (id) do update set public = true, file_size_limit = 52428800, allowed_mime_types = array['image/jpeg', 'video/mp4'];

drop policy if exists community_media_insert on storage.objects;
create policy community_media_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'community' and public.community_is_member() and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists community_media_delete on storage.objects;
create policy community_media_delete on storage.objects for delete to authenticated
  using (bucket_id = 'community' and ((storage.foldername(name))[1] = auth.uid()::text or public.community_is_admin()));
drop policy if exists community_media_read on storage.objects;
-- Files are served by their public URL without any policy. This select policy exists only because
-- storage upload/delete use RETURNING; it is limited to your own folder so nobody can LIST the bucket.
create policy community_media_read on storage.objects for select to authenticated
  using (bucket_id = 'community' and ((storage.foldername(name))[1] = auth.uid()::text or public.community_is_admin()));

-- ---------- version marker so the app can tell the server is ready ----------
create or replace function public.community_version() returns jsonb
language sql stable as $$ select jsonb_build_object('version', 4) $$;
grant execute on function public.community_version() to authenticated;
