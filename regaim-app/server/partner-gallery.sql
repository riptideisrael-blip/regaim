-- A shared gallery for two parents: each moment one of them keeps is shared automatically with the other one only
-- (not with the rest of the family circle). Run once in Supabase: SQL Editor -> New query -> paste -> Run.
-- Needs the family circle (secure-family-circle-v3.sql) already set up.

-- Who shares with whom (inside one circle). It works only when both chose each other.
create table if not exists public.family_partners (
  circle_id uuid not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  partner_id uuid not null references auth.users(id) on delete cascade,
  since timestamptz not null default now(),
  primary key (circle_id, user_id)
);
alter table public.family_partners enable row level security;
drop policy if exists family_partners_read on public.family_partners;
create policy family_partners_read on public.family_partners for select to authenticated
  using (user_id = auth.uid() or partner_id = auth.uid());
-- Written only through set_family_partner (below).

create or replace function public.family_are_partners(p_circle uuid, a uuid, b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select a is not null and b is not null and a <> b
    and exists (select 1 from public.family_partners x where x.circle_id = p_circle and x.user_id = a and x.partner_id = b)
    and exists (select 1 from public.family_partners y where y.circle_id = p_circle and y.user_id = b and y.partner_id = a);
$$;

-- Choose (or clear, with null) the other parent. Both must be members of the circle.
create or replace function public.set_family_partner(p_circle uuid, p_partner uuid) returns table(partner uuid, mutual boolean)
language plpgsql security definer set search_path = '' as $$
begin
  if not public.family_is_member(p_circle) then raise exception 'not a member'; end if;
  if p_partner is null then
    delete from public.family_partners where circle_id = p_circle and user_id = auth.uid();
    return query select null::uuid, false;
    return;
  end if;
  if p_partner = auth.uid() or not exists (select 1 from public.family_memberships m where m.circle_id = p_circle and m.user_id = p_partner) then
    raise exception 'not a member of this circle';
  end if;
  insert into public.family_partners(circle_id, user_id, partner_id) values (p_circle, auth.uid(), p_partner)
  on conflict (circle_id, user_id) do update set partner_id = excluded.partner_id, since = now();
  return query select p_partner, public.family_are_partners(p_circle, auth.uid(), p_partner);
end $$;

-- My partner in this circle, and whether they chose me too.
create or replace function public.family_partner_status(p_circle uuid) returns table(partner uuid, mutual boolean)
language sql stable security definer set search_path = '' as $$
  select x.partner_id, public.family_are_partners(p_circle, auth.uid(), x.partner_id)
  from public.family_partners x where x.circle_id = p_circle and x.user_id = auth.uid();
$$;

-- A post can be for one person only (the partner): only the author and that person see it.
alter table public.posts add column if not exists only_for uuid;

drop policy if exists family_posts_private on public.posts;
create policy family_posts_private on public.posts as restrictive for select to authenticated
  using (only_for is null or author_id = auth.uid() or only_for = auth.uid());
drop policy if exists family_posts_private_write on public.posts;
create policy family_posts_private_write on public.posts as restrictive for insert to authenticated
  with check (only_for is null or public.family_are_partners(circle_id, auth.uid(), only_for));

-- Reactions and comments on such a post: only those two as well.
create or replace function public.family_can_read_post(p_post_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.posts p where p.id = p_post_id and public.family_is_member(p.circle_id)
    and (p.only_for is null or p.author_id = auth.uid() or p.only_for = auth.uid()));
$$;

-- Their files: <circle>/<author>/p/<partner>/... can be read only by the author and the partner.
drop policy if exists family_storage_private on storage.objects;
create policy family_storage_private on storage.objects as restrictive for select to authenticated
  using (bucket_id <> 'moments' or split_part(name, '/', 3) <> 'p'
    or auth.uid()::text in (split_part(name, '/', 2), split_part(name, '/', 4)));
create or replace function public.family_storage_partner_ok(p_path text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
begin
  if split_part(p_path, '/', 3) <> 'p' then return true; end if;
  return public.family_are_partners(split_part(p_path, '/', 1)::uuid, auth.uid(), split_part(p_path, '/', 4)::uuid);
exception when others then return false;
end $$;
drop policy if exists family_storage_private_write on storage.objects;
create policy family_storage_private_write on storage.objects as restrictive for insert to authenticated
  with check (bucket_id <> 'moments' or public.family_storage_partner_ok(name));

revoke all on function public.family_are_partners(uuid, uuid, uuid), public.set_family_partner(uuid, uuid), public.family_partner_status(uuid), public.family_storage_partner_ok(text) from public, anon;
grant execute on function public.family_are_partners(uuid, uuid, uuid), public.set_family_partner(uuid, uuid), public.family_partner_status(uuid), public.family_storage_partner_ok(text) to authenticated;
