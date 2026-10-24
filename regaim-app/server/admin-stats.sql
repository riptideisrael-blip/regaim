-- Admin numbers for the owner's screen in the app (Profile > Account > "ניהול").
-- Run once in Supabase: SQL Editor > New query > paste > Run.
-- Only the admin email below can read the numbers; everyone else gets "not allowed".

create or replace function public.admin_stats() returns jsonb
language plpgsql security definer
set search_path = public, auth, storage
as $$
declare
  result jsonb;
  cposts bigint := 0;
  creports bigint := 0;
begin
  if not exists (
    select 1 from auth.users u
    where u.id = auth.uid()
      and lower(u.email) in ('yossefrevivo@gmail.com')
      and u.email_confirmed_at is not null
  ) then
    raise exception 'not allowed' using errcode = '42501';
  end if;

  if to_regclass('public.community_posts') is not null then
    execute 'select count(*) from public.community_posts' into cposts;
    execute 'select count(distinct post_id) from public.community_reports' into creports;
  end if;

  select jsonb_build_object(
    'community_posts', cposts,
    'community_reported', creports,
    'users',      (select count(*) from auth.users where coalesce(is_anonymous, false) = false),
    'users_7d',   (select count(*) from auth.users where coalesce(is_anonymous, false) = false and created_at > now() - interval '7 days'),
    'users_30d',  (select count(*) from auth.users where coalesce(is_anonymous, false) = false and created_at > now() - interval '30 days'),
    'active_7d',  (select count(*) from auth.users where coalesce(is_anonymous, false) = false and last_sign_in_at > now() - interval '7 days'),
    'google',     (select count(distinct user_id) from auth.identities where provider = 'google'),
    'guests',     (select count(*) from auth.users where coalesce(is_anonymous, false)),
    'circles',    (select count(*) from public.circles),
    'members',    (select count(*) from public.family_memberships),
    'posts',      (select count(*) from public.posts),
    'posts_7d',   (select count(*) from public.posts where created_at > now() - interval '7 days'),
    'comments',   (select count(*) from public.comments),
    'reactions',  (select count(*) from public.reactions),
    'messages',   (select count(*) from public.family_messages),
    'storage_mb', (select round(coalesce(sum((metadata->>'size')::bigint), 0) / 1048576.0, 1) from storage.objects where bucket_id = 'moments'),
    'signups',    (select coalesce(jsonb_agg(jsonb_build_object('day', d, 'n', c) order by d), '[]'::jsonb)
                   from (select created_at::date as d, count(*) as c from auth.users
                         where coalesce(is_anonymous, false) = false and created_at > now() - interval '14 days' group by 1) s),
    'recent',     (select coalesce(jsonb_agg(jsonb_build_object('email', email, 'name', raw_user_meta_data->>'full_name', 'created', created_at) order by created_at desc), '[]'::jsonb)
                   from (select email, raw_user_meta_data, created_at from auth.users
                         where coalesce(is_anonymous, false) = false order by created_at desc limit 15) r)
  ) into result;
  return result;
end;
$$;

revoke all on function public.admin_stats() from public, anon;
grant execute on function public.admin_stats() to authenticated;
