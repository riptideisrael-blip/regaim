-- Regaim v3: OPT-IN migration. Back up first and deploy matching Android/web clients.
-- Existing data is retained. Legacy author/owner IDs are NOT guessed from names.
-- Old six-character invite links are revoked. Existing families require explicit
-- administrator mapping to Auth users; see server/SECURE-FAMILY-DEPLOYMENT.md.
begin;
create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

-- pgcrypto may already live in another schema; do not relocate it or break dependants.
create or replace function public.family_invite_token() returns text
language plpgsql security definer set search_path = '' as $$
declare ext_schema text; result text;
begin
  select n.nspname into ext_schema from pg_catalog.pg_extension e join pg_catalog.pg_namespace n on n.oid=e.extnamespace where e.extname='pgcrypto';
  if ext_schema is null then raise exception 'pgcrypto is required'; end if;
  execute format('select upper(encode(%I.gen_random_bytes(16), ''hex''))',ext_schema) into result;
  return result;
end;
$$;
revoke all on function public.family_invite_token() from public,anon,authenticated;

alter table public.circles add column if not exists owner_id uuid references auth.users(id);
alter table public.posts add column if not exists author_id uuid references auth.users(id) default auth.uid();
alter table public.posts add column if not exists caption text;
alter table public.posts add column if not exists style text default 'plain';
alter table public.posts add column if not exists memory_id text;
alter table public.reactions add column if not exists author_id uuid references auth.users(id) default auth.uid();
alter table public.comments add column if not exists author_id uuid references auth.users(id) default auth.uid();

create table if not exists public.family_memberships (
  circle_id uuid not null references public.circles(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 100),
  relationship text not null default 'בן/בת משפחה',
  avatar_url text not null default '',
  joined_at timestamptz not null default now(),
  primary key (circle_id, user_id)
);
create table if not exists public.family_invites (
  circle_id uuid primary key references public.circles(id) on delete cascade,
  token text unique not null check (token ~ '^[A-F0-9]{32}$'),
  expires_at timestamptz not null default (now() + interval '90 days')
);
create table if not exists public.family_messages (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid not null references public.circles(id) on delete cascade,
  post_id uuid references public.posts(id) on delete cascade,
  author_id uuid not null references auth.users(id) default auth.uid(),
  author text not null check (char_length(author) between 1 and 100),
  body text not null check (char_length(trim(body)) between 1 and 4000),
  created_at timestamptz not null default now()
);
create index if not exists family_messages_thread on public.family_messages(circle_id, post_id, created_at desc);
create index if not exists posts_family_memory on public.posts(circle_id, memory_id);
create index if not exists comments_post on public.comments(post_id, created_at);
create unique index if not exists reactions_identity on public.reactions(post_id, author_id, emoji);
alter table public.reactions drop constraint if exists reactions_post_id_member_emoji_key;

-- On first installation only, replace unsafe short codes with unguessable tokens.
insert into public.family_invites(circle_id, token)
select id, public.family_invite_token() from public.circles
on conflict(circle_id) do nothing;
update public.circles c set code = i.token from public.family_invites i where i.circle_id = c.id;

create or replace function public.family_is_member(p_circle_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and exists (
    select 1 from public.family_memberships m where m.circle_id = p_circle_id and m.user_id = auth.uid()
  );
$$;
create or replace function public.family_can_read_post(p_post_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.posts p where p.id = p_post_id and public.family_is_member(p.circle_id));
$$;
create or replace function public.family_storage_member(p_path text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.family_memberships m
    where m.user_id = auth.uid() and m.circle_id::text = split_part(p_path, '/', 1));
$$;
create or replace function public.family_circle_version() returns jsonb
language sql stable security definer set search_path = '' as $$ select jsonb_build_object('version', 3); $$;

create or replace function public.create_family_circle(p_name text, p_display_name text)
returns table(id uuid, code text, name text)
language plpgsql security definer set search_path = '' as $$
declare cid uuid; tok text;
begin
  if auth.uid() is null then raise exception 'Sign in required' using errcode = '42501'; end if;
  if coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then raise exception 'A permanent account is required to create a family' using errcode = '42501'; end if;
  if char_length(trim(p_name)) not between 1 and 100 or char_length(trim(p_display_name)) not between 1 and 100 then raise exception 'Invalid family name'; end if;
  tok := public.family_invite_token();
  insert into public.circles(code,name,owner_id) values(tok,trim(p_name),auth.uid()) returning circles.id into cid;
  insert into public.family_memberships(circle_id,user_id,display_name,relationship) values(cid,auth.uid(),trim(p_display_name),'מנהל/ת המעגל');
  insert into public.family_invites(circle_id,token) values(cid,tok);
  return query select c.id,tok,c.name from public.circles c where c.id=cid;
end;
$$;
create or replace function public.redeem_family_invite(p_token text, p_display_name text)
returns table(id uuid, code text, name text)
language plpgsql security definer set search_path = '' as $$
declare cid uuid;
begin
  if auth.uid() is null then raise exception 'Sign in required' using errcode = '42501'; end if;
  if char_length(trim(p_display_name)) not between 1 and 100 then raise exception 'Invalid display name'; end if;
  select i.circle_id into cid from public.family_invites i join public.circles c on c.id=i.circle_id
    where i.token=upper(trim(p_token)) and i.expires_at>now() and c.owner_id is not null;
  if cid is null then raise exception 'Invitation expired, revoked, or family migration not completed' using errcode='42501'; end if;
  insert into public.family_memberships(circle_id,user_id,display_name) values(cid,auth.uid(),trim(p_display_name))
    on conflict(circle_id,user_id) do update set display_name=excluded.display_name;
  return query select c.id,i.token,c.name from public.circles c join public.family_invites i on i.circle_id=c.id where c.id=cid;
end;
$$;
create or replace function public.family_circle_status(p_circle_id uuid)
returns table(id uuid, code text, name text)
language sql stable security definer set search_path = '' as $$
  select c.id,case when i.expires_at>now() then i.token else '' end,c.name
  from public.circles c left join public.family_invites i on i.circle_id=c.id
  where c.id=p_circle_id and public.family_is_member(c.id);
$$;
create or replace function public.create_family_invite(p_circle_id uuid)
returns table(id uuid, code text, name text)
language plpgsql security definer set search_path = '' as $$
declare tok text;
begin
  if not exists(select 1 from public.circles c where c.id=p_circle_id and c.owner_id=auth.uid()) then raise exception 'Only the family owner can issue or rotate invitations' using errcode='42501'; end if;
  tok := public.family_invite_token();
  insert into public.family_invites(circle_id,token) values(p_circle_id,tok)
    on conflict(circle_id) do update set token=excluded.token,expires_at=now()+interval '90 days';
  update public.circles c set code=tok where c.id=p_circle_id;
  return query select c.id,tok,c.name from public.circles c where c.id=p_circle_id;
end;
$$;
create or replace function public.get_family_members(p_circle_id uuid)
returns table(user_id uuid, display_name text, relationship text, avatar_url text)
language sql stable security definer set search_path = '' as $$
  select m.user_id,m.display_name,m.relationship,m.avatar_url from public.family_memberships m
  where m.circle_id=p_circle_id and public.family_is_member(p_circle_id) order by m.joined_at;
$$;

-- Ensure update cannot move content to another family or steal authorship.
create or replace function public.family_post_identity_immutable() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.id is distinct from old.id or new.circle_id is distinct from old.circle_id or new.author_id is distinct from old.author_id then
    raise exception 'Post identity is immutable' using errcode='42501';
  end if;
  return new;
end;
$$;
drop trigger if exists family_post_identity on public.posts;
create trigger family_post_identity before update on public.posts for each row execute function public.family_post_identity_immutable();

-- Remove the supplied unsafe policies and install membership fences.
drop policy if exists "proto circles" on public.circles;
drop policy if exists "proto posts" on public.posts;
drop policy if exists "proto reactions" on public.reactions;
drop policy if exists "proto comments" on public.comments;
alter table public.circles enable row level security;
alter table public.posts enable row level security;
alter table public.reactions enable row level security;
alter table public.comments enable row level security;
alter table public.family_memberships enable row level security;
alter table public.family_invites enable row level security;
alter table public.family_messages enable row level security;

-- Restrictive policies prevent another permissive policy from reopening access.
drop policy if exists family_circle_fence on public.circles;
create policy family_circle_fence on public.circles as restrictive for all to authenticated using(public.family_is_member(id)) with check(public.family_is_member(id));
drop policy if exists family_circles_read on public.circles;
create policy family_circles_read on public.circles for select to authenticated using(public.family_is_member(id));
drop policy if exists family_post_fence on public.posts;
create policy family_post_fence on public.posts as restrictive for all to authenticated using(public.family_is_member(circle_id)) with check(public.family_is_member(circle_id));
drop policy if exists family_posts_read on public.posts;
create policy family_posts_read on public.posts for select to authenticated using(public.family_is_member(circle_id));
drop policy if exists family_posts_insert on public.posts;
create policy family_posts_insert on public.posts for insert to authenticated with check(public.family_is_member(circle_id) and author_id=auth.uid());
drop policy if exists family_posts_update on public.posts;
create policy family_posts_update on public.posts for update to authenticated using(author_id=auth.uid()) with check(author_id=auth.uid());
drop policy if exists family_posts_delete on public.posts;
create policy family_posts_delete on public.posts for delete to authenticated using(author_id=auth.uid());
drop policy if exists family_reaction_fence on public.reactions;
create policy family_reaction_fence on public.reactions as restrictive for all to authenticated using(public.family_can_read_post(post_id)) with check(public.family_can_read_post(post_id));
drop policy if exists family_reactions_read on public.reactions;
create policy family_reactions_read on public.reactions for select to authenticated using(public.family_can_read_post(post_id));
drop policy if exists family_reactions_insert on public.reactions;
create policy family_reactions_insert on public.reactions for insert to authenticated with check(author_id=auth.uid() and emoji in ('❤️','😂','😍','🥹','👏'));
drop policy if exists family_reactions_delete on public.reactions;
create policy family_reactions_delete on public.reactions for delete to authenticated using(author_id=auth.uid());
drop policy if exists family_comment_fence on public.comments;
create policy family_comment_fence on public.comments as restrictive for all to authenticated using(public.family_can_read_post(post_id)) with check(public.family_can_read_post(post_id));
drop policy if exists family_comments_read on public.comments;
create policy family_comments_read on public.comments for select to authenticated using(public.family_can_read_post(post_id));
drop policy if exists family_comments_insert on public.comments;
create policy family_comments_insert on public.comments for insert to authenticated with check(author_id=auth.uid() and char_length(trim(body)) between 1 and 4000);
drop policy if exists family_comments_delete on public.comments;
create policy family_comments_delete on public.comments for delete to authenticated using(author_id=auth.uid());
drop policy if exists family_members_read on public.family_memberships;
create policy family_members_read on public.family_memberships for select to authenticated using(public.family_is_member(circle_id));
drop policy if exists family_message_fence on public.family_messages;
create policy family_message_fence on public.family_messages as restrictive for all to authenticated
  using(public.family_is_member(circle_id)) with check(public.family_is_member(circle_id));
drop policy if exists family_messages_read on public.family_messages;
create policy family_messages_read on public.family_messages for select to authenticated using(public.family_is_member(circle_id));
drop policy if exists family_messages_insert on public.family_messages;
create policy family_messages_insert on public.family_messages for insert to authenticated with check(author_id=auth.uid() and
  (post_id is null or exists(select 1 from public.posts p where p.id=post_id and p.circle_id=family_messages.circle_id)));
drop policy if exists family_messages_delete on public.family_messages;
create policy family_messages_delete on public.family_messages for delete to authenticated using(author_id=auth.uid());

-- Identity fences supplement the membership fences for every mutation operation.
do $$
declare tab text;
begin
  foreach tab in array array['posts','reactions','comments','family_messages'] loop
    execute format('drop policy if exists family_author_insert_fence on public.%I',tab);
    execute format('create policy family_author_insert_fence on public.%I as restrictive for insert to authenticated with check(author_id=auth.uid())',tab);
    execute format('drop policy if exists family_author_delete_fence on public.%I',tab);
    execute format('create policy family_author_delete_fence on public.%I as restrictive for delete to authenticated using(author_id=auth.uid())',tab);
  end loop;
end $$;
drop policy if exists family_author_update_fence on public.posts;
create policy family_author_update_fence on public.posts as restrictive for update to authenticated using(author_id=auth.uid()) with check(author_id=auth.uid());

revoke all on public.circles,public.posts,public.reactions,public.comments,public.family_memberships,public.family_invites,public.family_messages from anon;
revoke all on public.circles,public.family_memberships,public.family_invites,public.family_messages,public.posts,public.reactions,public.comments from authenticated;
grant select on public.circles,public.family_memberships to authenticated;
grant select,insert,update,delete on public.posts to authenticated;
grant select,insert,delete on public.reactions,public.comments,public.family_messages to authenticated;

insert into storage.buckets(id,name,public) values('moments','moments',false)
on conflict(id) do update set public=false;
drop policy if exists "proto moments read" on storage.objects;
drop policy if exists "proto moments upload" on storage.objects;
drop policy if exists "proto moments delete" on storage.objects;
drop policy if exists family_storage_fence on storage.objects;
create policy family_storage_fence on storage.objects as restrictive for all to authenticated
  using(bucket_id<>'moments' or public.family_storage_member(name))
  with check(bucket_id<>'moments' or public.family_storage_member(name));
drop policy if exists family_storage_read on storage.objects;
create policy family_storage_read on storage.objects for select to authenticated using(bucket_id='moments' and public.family_storage_member(name));
drop policy if exists family_storage_upload on storage.objects;
create policy family_storage_upload on storage.objects for insert to authenticated
  with check(bucket_id='moments' and public.family_storage_member(name) and split_part(name,'/',2)=auth.uid()::text);
drop policy if exists family_storage_delete on storage.objects;
create policy family_storage_delete on storage.objects for delete to authenticated
  using(bucket_id='moments' and public.family_storage_member(name) and owner_id=auth.uid()::text);

-- Public access remains blocked even if a project had another permissive policy.
drop policy if exists family_storage_public_fence on storage.objects;
create policy family_storage_public_fence on storage.objects as restrictive for all to anon using(bucket_id<>'moments') with check(bucket_id<>'moments');
drop policy if exists family_storage_upload_owner_fence on storage.objects;
create policy family_storage_upload_owner_fence on storage.objects as restrictive for insert to authenticated
  with check(bucket_id<>'moments' or split_part(name,'/',2)=auth.uid()::text);
drop policy if exists family_storage_delete_owner_fence on storage.objects;
create policy family_storage_delete_owner_fence on storage.objects as restrictive for delete to authenticated
  using(bucket_id<>'moments' or owner_id=auth.uid()::text);

-- Functions are callable only by Auth users, never with the publishable key alone.
revoke all on function public.family_is_member(uuid),public.family_can_read_post(uuid),public.family_storage_member(text),public.family_circle_version(),public.create_family_circle(text,text),public.redeem_family_invite(text,text),public.family_circle_status(uuid),public.create_family_invite(uuid),public.get_family_members(uuid) from public,anon;
grant execute on function public.family_is_member(uuid),public.family_can_read_post(uuid),public.family_storage_member(text),public.family_circle_version(),public.create_family_circle(text,text),public.redeem_family_invite(text,text),public.family_circle_status(uuid),public.create_family_invite(uuid),public.get_family_members(uuid) to authenticated;
commit;
