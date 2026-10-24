-- "Moments team" posts: a post is official only when the community admin publishes it. The server decides; a
-- regular user can't mark their own post as official, at insert or later. Run once, after community-v4.sql.
-- One statement per line (the Supabase editor on phones adds brackets to multi-line pastes).
alter table public.community_posts add column if not exists official boolean not null default false;
create or replace function public.community_mark_official() returns trigger language plpgsql security definer set search_path = public as $$ begin if not public.community_is_admin() and lower(trim(new.author_name)) in ('moments', 'צוות moments') then new.author_name := 'משתמש'; end if; if tg_op = 'INSERT' then new.official := public.community_is_admin() and new.author_name = 'Moments'; elsif not public.community_is_admin() then new.official := old.official; end if; return new; end $$;
drop trigger if exists community_mark_official on public.community_posts;
create trigger community_mark_official before insert or update on public.community_posts for each row execute function public.community_mark_official();
