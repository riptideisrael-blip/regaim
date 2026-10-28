-- Sharing the family file between the two parents (receipts, documents, warranties, vouchers).
-- Run once in Supabase: SQL Editor -> New query -> paste -> Run. Needs partner-gallery.sql (family_are_partners).
-- Adds one new table and its rules. Nothing existing is changed or deleted.
--
-- Each parent's phone sends its own items only after that parent agreed to share (in the app), and reads the other
-- parent's items only after agreeing too. Only the owner and the chosen partner can read a row; only the owner can
-- write or remove it. Files go to the existing private path <circle>/<owner>/p/<partner>/ff/... (partner-only).

create table if not exists public.family_file_items (
  circle_id uuid not null,
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  item_id text not null check (length(item_id) between 1 and 64),
  partner_id uuid not null references auth.users(id) on delete cascade,
  data jsonb not null,
  file_path text check (file_path is null or length(file_path) < 400),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  primary key (circle_id, owner_id, item_id)
);
alter table public.family_file_items enable row level security;

drop policy if exists family_file_read on public.family_file_items;
create policy family_file_read on public.family_file_items for select to authenticated
  using (owner_id = auth.uid()
    or (partner_id = auth.uid() and public.family_are_partners(circle_id, owner_id, auth.uid())));

drop policy if exists family_file_insert on public.family_file_items;
create policy family_file_insert on public.family_file_items for insert to authenticated
  with check (owner_id = auth.uid() and public.family_are_partners(circle_id, auth.uid(), partner_id));

drop policy if exists family_file_update on public.family_file_items;
create policy family_file_update on public.family_file_items for update to authenticated
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid() and public.family_are_partners(circle_id, auth.uid(), partner_id));

drop policy if exists family_file_delete on public.family_file_items;
create policy family_file_delete on public.family_file_items for delete to authenticated
  using (owner_id = auth.uid());

revoke all on public.family_file_items from anon;
grant select, insert, update, delete on public.family_file_items to authenticated;
