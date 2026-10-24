-- READ ONLY (SELECT only, changes nothing). Lists every rule on the family tables and storage that mentions
-- anon / public, one per row, and whether it OPENS access (permissive) or only BLOCKS it (restrictive).
select tablename as table_name,
       policyname as rule,
       case when permissive = 'RESTRICTIVE' then 'blocks only (OK)' else 'OPENS access' end as kind,
       cmd as action,
       array_to_string(roles, ',') as roles,
       left(coalesce(qual, '') || ' | ' || coalesce(with_check, ''), 160) as condition
from pg_policies
where (tablename in ('circles','posts','reactions','comments','family_memberships','family_invites','family_messages')
       or (schemaname = 'storage' and tablename = 'objects'))
  and ('anon' = any(roles) or 'public' = any(roles))
order by kind desc, tablename, policyname;
