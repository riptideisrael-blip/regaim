-- The key each parent's backup (to their own Google Drive) is encrypted with. One per account, made the first time
-- it's asked for, readable only by that account through backup_key(). The backup files themselves are not here.
-- Supabase: SQL Editor > New query > paste all > Run. Safe to run again. Additive only.
create table if not exists public.backup_keys (
  user_id uuid primary key references auth.users(id) on delete cascade,
  key text not null,
  created_at timestamptz not null default now()
);
alter table public.backup_keys enable row level security;
-- No policies: nobody reads the table directly, only through the function below (as the signed-in account).
revoke all on public.backup_keys from anon, authenticated;

create or replace function public.backup_key() returns text
language plpgsql security definer set search_path = public, extensions as $$
declare k text;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  select key into k from public.backup_keys where user_id = auth.uid();
  if k is null then
    insert into public.backup_keys(user_id, key) values (auth.uid(), encode(extensions.gen_random_bytes(32), 'base64'))
      on conflict (user_id) do nothing;
    select key into k from public.backup_keys where user_id = auth.uid();
  end if;
  return k;
end $$;
revoke all on function public.backup_key() from public, anon;
grant execute on function public.backup_key() to authenticated;
