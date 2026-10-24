-- Payments (Google Play) + sharing books in the community with a rating and a review. Run once, after book-credits-2.sql and community-v6-official.sql.
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again. One statement per line (the Supabase editor on phones adds brackets to multi-line pastes).
-- Nothing here turns payments on: that's film_settings "payments" = "on" (see the end).

-- ---------- 1. Google Play purchases: each purchase adds its credits exactly once ----------
create table if not exists public.play_purchases (token text primary key, user_id uuid not null references auth.users(id) on delete cascade, product text not null, credits int not null, order_id text not null default '', created_at timestamptz not null default now());
alter table public.play_purchases enable row level security;
create or replace function public.film_grant_purchase(p_user uuid, p_token text, p_product text, p_credits int, p_order text) returns text language plpgsql security definer set search_path = public as $$ declare owner uuid; begin insert into play_purchases(token, user_id, product, credits, order_id) values (p_token, p_user, p_product, p_credits, coalesce(p_order, '')) on conflict (token) do nothing; if not found then select user_id into owner from play_purchases where token = p_token; if owner = p_user then return 'already'; end if; return 'other'; end if; insert into film_credits(user_id) values (p_user) on conflict do nothing; update film_credits set balance = balance + p_credits, updated_at = now() where user_id = p_user; return 'granted'; end $$;
revoke all on function public.film_grant_purchase(uuid, text, text, int, text) from public, anon, authenticated;
grant execute on function public.film_grant_purchase(uuid, text, text, int, text) to service_role;

-- ---------- 2. A receipt for every book really made (so a review in the community can be marked "real book") ----------
create table if not exists public.book_receipts (user_id uuid not null references auth.users(id) on delete cascade, ref text not null, created_at timestamptz not null default now(), primary key (user_id, ref));
alter table public.book_receipts enable row level security;
create or replace function public.film_reserve_once(p_user uuid, p_ref text, p_again boolean) returns text language plpgsql security definer set search_path = public as $$ declare r film_credits%rowtype; begin insert into film_credits(user_id) values (p_user) on conflict do nothing; select * into r from film_credits where user_id = p_user for update; if r.active_ref = p_ref and r.active_until > now() and r.calls < 40 then return 'active'; end if; if r.active_ref = p_ref and not coalesce(p_again, false) then return 'expired'; end if; if r.balance <= 0 then return 'empty'; end if; update film_credits set balance = balance - 1, active_until = now() + interval '3 hours', calls = 0, active_ref = p_ref, updated_at = now() where user_id = p_user; if p_ref like 'book%' then insert into book_receipts(user_id, ref) values (p_user, regexp_replace(p_ref, '_anim$', '')) on conflict do nothing; end if; return 'charged'; end $$;
revoke all on function public.film_reserve_once(uuid, text, boolean) from public, anon, authenticated;
grant execute on function public.film_reserve_once(uuid, text, boolean) to service_role;

-- ---------- 3. Books in the community ----------
alter table public.community_posts drop constraint if exists community_posts_kind_check;
alter table public.community_posts add constraint community_posts_kind_check check (kind in ('photo', 'video', 'book'));
-- pages: [{"p": "<storage path>", "t": "<the page's words>"}], cover first. rating 1-5 and review: the parent who made the book.
alter table public.community_posts add column if not exists pages jsonb not null default '[]'::jsonb;
alter table public.community_posts add column if not exists rating smallint check (rating between 1 and 5);
alter table public.community_posts add column if not exists review text not null default '' check (char_length(review) <= 600);
alter table public.community_posts add column if not exists book_ref text not null default '' check (char_length(book_ref) <= 80);
alter table public.community_posts add column if not exists verified boolean not null default false;
-- Every page must be a file in the author's own folder; "verified" (a real book made in the app, by this account) is decided here, never by the app.
create or replace function public.community_check_book() returns trigger language plpgsql security definer set search_path = public as $$ declare e jsonb; begin if jsonb_typeof(new.pages) <> 'array' or jsonb_array_length(new.pages) > 14 then raise exception 'pages'; end if; for e in select * from jsonb_array_elements(new.pages) loop if coalesce(e->>'p', '') not like new.author_id::text || '/%' or (e->>'p') like '%..%' or char_length(e->>'p') > 300 or char_length(coalesce(e->>'t', '')) > 800 then raise exception 'page'; end if; end loop; if tg_op = 'INSERT' then new.verified := new.kind = 'book' and new.book_ref <> '' and exists (select 1 from book_receipts r where r.user_id = new.author_id and r.ref = new.book_ref); elsif not public.community_is_admin() then new.verified := old.verified; end if; return new; end $$;
drop trigger if exists community_check_book on public.community_posts;
create trigger community_check_book before insert or update on public.community_posts for each row execute function public.community_check_book();
-- The pages of a book post open (signed links) for the members who may see that post, like its cover.
drop policy if exists community_media_read on storage.objects;
create policy community_media_read on storage.objects for select to authenticated using (bucket_id = 'community' and ((storage.foldername(name))[1] = auth.uid()::text or public.community_is_admin() or exists (select 1 from public.community_posts p where p.media_path = storage.objects.name or p.cover_path = storage.objects.name or p.pages @> jsonb_build_array(jsonb_build_object('p', storage.objects.name)))));

-- ---------- 4. Turning payments on (only when Play Console and the GOOGLE_PLAY_SERVICE_ACCOUNT secret are ready) ----------
-- insert into public.film_settings(key, value) values ('payments', 'on') on conflict (key) do update set value = excluded.value;
-- New accounts get 3 free books today. With payments on, you may want 0 (or 1 to try):
-- alter table public.film_credits alter column balance set default 0;
