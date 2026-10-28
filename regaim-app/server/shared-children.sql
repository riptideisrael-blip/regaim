-- Shared children (stage 1 of the family file). Adds one table and one function; changes no existing table or data.
-- Safe to run again. Until it runs, the app keeps each phone's own list of children, as before.
--
-- Who sees what:
--   * Everyone in the family circle sees the children's names and birthdays (they already see the shared moments).
--   * Only a parent changes them: the circle's owner, or one of the two parents who chose each other as partners.
--   * Nobody deletes a row: a parent marks it deleted, so the other parent's phone removes the child too.

create table if not exists public.family_children (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid not null references public.circles(id) on delete cascade,
  name text not null check (char_length(btrim(name)) between 1 and 60),
  birth date,
  created_by uuid default auth.uid(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index if not exists family_children_circle on public.family_children(circle_id);
alter table public.family_children enable row level security;

-- A parent in this circle: its owner, or one of the two partners.
create or replace function public.family_is_parent(p_circle uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null
     and public.family_is_member(p_circle)
     and (exists (select 1 from public.circles c where c.id = p_circle and c.owner_id = auth.uid())
          or exists (select 1 from public.family_partners x
                      where x.circle_id = p_circle and x.user_id = auth.uid()
                        and public.family_are_partners(p_circle, auth.uid(), x.partner_id)));
$$;
revoke all on function public.family_is_parent(uuid) from public, anon;
grant execute on function public.family_is_parent(uuid) to authenticated;

drop policy if exists family_children_read on public.family_children;
create policy family_children_read on public.family_children for select to authenticated
  using (public.family_is_member(circle_id));

drop policy if exists family_children_add on public.family_children;
create policy family_children_add on public.family_children for insert to authenticated
  with check (public.family_is_parent(circle_id) and created_by = auth.uid() and deleted_at is null);

drop policy if exists family_children_change on public.family_children;
create policy family_children_change on public.family_children for update to authenticated
  using (public.family_is_parent(circle_id))
  with check (public.family_is_parent(circle_id));
-- No delete policy: rows are only marked deleted.

revoke all on public.family_children from anon;
grant select, insert, update on public.family_children to authenticated;

-- Check: all rows should say true.
select 'table' as what, exists (select 1 from information_schema.tables where table_schema = 'public' and table_name = 'family_children') as ok
union all select 'rls on', (select relrowsecurity from pg_class where oid = 'public.family_children'::regclass)
union all select '3 rules', (select count(*) = 3 from pg_policies where schemaname = 'public' and tablename = 'family_children')
union all select 'no anon', not has_table_privilege('anon', 'public.family_children', 'select');
