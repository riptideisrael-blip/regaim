-- READ ONLY: checks how the live server is set up. Changes nothing (only SELECT). Safe on the live project.
-- Supabase: SQL Editor > New query > paste all > Run. Send a screenshot of the table it shows.
-- Each row: what was checked, OK or PROBLEM, and details.
select check_name, status, details from (
  -- 1. Old prototype rules (open to anyone, "proto ...") must be gone.
  select 1 as n, 'old prototype rules removed' as check_name,
    case when count(*) = 0 then 'OK' else 'PROBLEM' end as status,
    coalesce(string_agg(tablename || ': ' || policyname, ', '), 'none left') as details
  from pg_policies where policyname ilike 'proto%'
  union all
  -- 2. No rule on the family tables or storage that lets anon (not signed in) in.
  select 2, 'no family rule open to anon',
    case when count(*) = 0 then 'OK' else 'PROBLEM' end,
    coalesce(string_agg(schemaname || '.' || tablename || ': ' || policyname, ', '), 'none')
  from pg_policies
  where (tablename in ('circles','posts','reactions','comments','family_memberships','family_invites','family_messages')
         or (schemaname = 'storage' and tablename = 'objects'))
    and ('anon' = any(roles) or 'public' = any(roles))
    and permissive = 'PERMISSIVE'  -- restrictive rules only block, they never open access
  union all
  -- 3. anon has no table rights on the family tables.
  select 3, 'anon has no table rights',
    case when count(*) = 0 then 'OK' else 'PROBLEM' end,
    coalesce(string_agg(table_name || ':' || privilege_type, ', '), 'none')
  from information_schema.role_table_grants
  where grantee = 'anon' and table_schema = 'public'
    and table_name in ('circles','posts','reactions','comments','family_memberships','family_invites','family_messages')
  union all
  -- 4. Row security is on for every family table.
  select 4, 'row security on',
    case when bool_and(c.relrowsecurity) then 'OK' else 'PROBLEM' end,
    string_agg(c.relname || '=' || c.relrowsecurity::text, ', ')
  from pg_class c join pg_namespace s on s.oid = c.relnamespace
  where s.nspname = 'public' and c.relname in ('circles','posts','reactions','comments','family_memberships','family_invites','family_messages')
  union all
  -- 5. The secure version (v3) and the leave/remove functions (v4) are installed.
  select 5, 'v3 + v4 functions installed',
    case when count(*) = 4 then 'OK' else 'PROBLEM' end,
    coalesce(string_agg(p.proname, ', '), 'none')
  from pg_proc p join pg_namespace s on s.oid = p.pronamespace
  where s.nspname = 'public' and p.proname in ('family_circle_version','leave_family','remove_family_member','family_is_owner')
  union all
  -- 6. Private buckets (family, community, print) are not public.
  select 6, 'media buckets private',
    case when bool_or(public) then 'PROBLEM' else 'OK' end,
    coalesce(string_agg(id || '=' || public::text, ', '), 'none')
  from storage.buckets where id in ('moments','community','print')
  union all
  -- 7. The family chat outbox column (family-chat-outbox.sql), for information.
  select 7, 'chat outbox column (optional)',
    case when count(*) = 1 then 'OK' else 'not run yet' end,
    case when count(*) = 1 then 'client_id present' else 'family-chat-outbox.sql not run' end
  from information_schema.columns
  where table_schema = 'public' and table_name = 'family_messages' and column_name = 'client_id'
  union all
  -- 8. Every table in public has row security on (a table without it is open to any signed-in user).
  select 8, 'row security on ALL public tables',
    case when count(*) = 0 then 'OK' else 'PROBLEM' end,
    coalesce(string_agg(c.relname, ', '), 'all on')
  from pg_class c join pg_namespace s on s.oid = c.relnamespace
  where s.nspname = 'public' and c.relkind = 'r' and not c.relrowsecurity
  union all
  -- 9. Which buckets are public (anyone with the link). Only pitzki-shared (Pitzki's own cartoons) should be.
  select 9, 'public buckets = only pitzki-shared',
    case when coalesce(bool_and(id = 'pitzki-shared'), true) then 'OK' else 'PROBLEM' end,
    coalesce(string_agg(id, ', '), 'none')
  from storage.buckets where public
  union all
  -- 10. The parents' shared gallery rules (partner-gallery.sql): private posts and private files.
  select 10, 'partner gallery rules installed',
    case when count(*) = 2 then 'OK' else 'PROBLEM' end,
    coalesce(string_agg(policyname, ', '), 'none')
  from pg_policies where policyname in ('family_posts_private', 'family_storage_private')
  union all
  -- 11. The app updates folder is private.
  select 11, 'app-updates private',
    case when count(*) = 0 then 'not run yet' when bool_or(public) then 'PROBLEM' else 'OK' end,
    coalesce(string_agg(id || '=' || public::text, ', '), 'app-updates.sql not run')
  from storage.buckets where id = 'app-updates'
  union all
  -- 12. No storage rule (any bucket) lets someone who isn't signed in read, add or change files.
  select 12, 'no storage rule open to anon',
    case when count(*) = 0 then 'OK' else 'PROBLEM' end,
    coalesce(string_agg(policyname || ' (' || cmd || ')', ', '), 'none')
  from pg_policies
  where schemaname = 'storage' and tablename = 'objects' and permissive = 'PERMISSIVE'
    and ('anon' = any(roles) or 'public' = any(roles))
  union all
  -- 13. For information: how many accounts, and how many of them are guest (anonymous) sign-ins.
  select 13, 'accounts (info)', 'info',
    count(*)::text || ' accounts, ' || count(*) filter (where is_anonymous)::text || ' guests'
  from auth.users
) r order by n;
