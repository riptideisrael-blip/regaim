-- Run ONLY on a disposable/local Supabase database after base setup + v3 migration.
-- Each fixture is rolled back; do not use a customer production database as a test target.
begin;
insert into auth.users(id,email) values
 ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','rls-a@invalid.test'),
 ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2','rls-b@invalid.test'),
 ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3','rls-c@invalid.test');
insert into public.circles(id,code,name,owner_id) values
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','11111111111111111111111111111111','משפחה א','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'),
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2','22222222222222222222222222222222','משפחה ב','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3');
insert into public.family_memberships(circle_id,user_id,display_name) values
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','אותו שם'),
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2','אותו שם'),
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3','אדם אחר');
insert into public.family_invites(circle_id,token) values
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','11111111111111111111111111111111'),
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2','22222222222222222222222222222222');
insert into public.posts(id,circle_id,author_id,author,title) values
 ('cccccccc-cccc-4ccc-8ccc-ccccccccccc1','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','אותו שם','רגע א'),
 ('cccccccc-cccc-4ccc-8ccc-ccccccccccc2','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3','אדם אחר','רגע ב');
insert into storage.objects(bucket_id,name,owner_id) values
 ('moments','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1/test.jpg','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'),
 ('moments','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3/test.jpg','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3');

set local role anon;
select set_config('request.jwt.claim.sub','',true);
select set_config('request.jwt.claims','{}',true);
do $$ declare total integer; begin
  begin
    select count(*) into total from public.posts;
    if total<>0 then raise exception 'FAIL: unauthenticated table disclosure'; end if;
  exception when insufficient_privilege then null; end;
  if exists(select 1 from storage.objects where bucket_id='moments') then raise exception 'FAIL: unauthenticated storage read'; end if;
  begin
    perform public.family_circle_version();
    raise exception 'FAIL: unauthenticated RPC allowed';
  exception when insufficient_privilege then null; end;
  raise notice 'PASS: unauthenticated data, Storage and RPC access blocked';
end $$;

reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',true);
select set_config('request.jwt.claims','{"sub":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1","role":"authenticated","is_anonymous":false}',true);
do $$ declare total integer; begin
  select count(*) into total from public.posts where id in ('cccccccc-cccc-4ccc-8ccc-ccccccccccc1','cccccccc-cccc-4ccc-8ccc-ccccccccccc2');
  if total<>1 then raise exception 'FAIL: cross-family posts read'; end if;
  select count(*) into total from storage.objects where bucket_id='moments' and name like 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb%';
  if total<>1 then raise exception 'FAIL: cross-family Storage read'; end if;
  begin
    insert into public.comments(post_id,author_id,member,body) values('cccccccc-cccc-4ccc-8ccc-ccccccccccc1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2','אותו שם','spoof');
    raise exception 'FAIL: spoofed comment author accepted';
  exception when insufficient_privilege then null; end;
  begin
    insert into public.family_messages(circle_id,post_id,author_id,author,body) values('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','cccccccc-cccc-4ccc-8ccc-ccccccccccc2','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','אותו שם','cross family');
    raise exception 'FAIL: message linked to another family post';
  exception when insufficient_privilege then null; end;
  begin
    insert into public.posts(circle_id,author_id,author,title) values('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2','אותו שם','spoof');
    raise exception 'FAIL: spoofed post author accepted';
  exception when insufficient_privilege then null; end;
  insert into public.reactions(post_id,author_id,member,emoji) values('cccccccc-cccc-4ccc-8ccc-ccccccccccc1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','אותו שם','❤️');
  insert into public.comments(post_id,author_id,member,body) values('cccccccc-cccc-4ccc-8ccc-ccccccccccc1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','אותו שם','התגובה נשמרה');
  insert into public.family_messages(circle_id,post_id,author_id,author,body) values('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1','cccccccc-cccc-4ccc-8ccc-ccccccccccc1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','אותו שם','שיחה על הרגע');
  raise notice 'PASS: member reads only its family, spoof/cross-post rejected, real writes accepted';
end $$;

select set_config('request.jwt.claim.sub','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2',true);
select set_config('request.jwt.claims','{"sub":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2","role":"authenticated","is_anonymous":true}',true);
do $$ declare total integer; begin
  insert into public.reactions(post_id,author_id,member,emoji) values('cccccccc-cccc-4ccc-8ccc-ccccccccccc1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2','אותו שם','❤️');
  select count(*) into total from public.reactions where post_id='cccccccc-cccc-4ccc-8ccc-ccccccccccc1' and emoji='❤️';
  if total<>2 then raise exception 'FAIL: alias collision overwrote another person'; end if;
  update public.posts set title='stolen' where id='cccccccc-cccc-4ccc-8ccc-ccccccccccc1';
  get diagnostics total = row_count;
  if total<>0 then raise exception 'FAIL: identical display name stole ownership'; end if;
  delete from public.posts where id='cccccccc-cccc-4ccc-8ccc-ccccccccccc1';
  get diagnostics total = row_count;
  if total<>0 then raise exception 'FAIL: member deleted another author post'; end if;
  delete from storage.objects where bucket_id='moments' and name like 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1/%';
  get diagnostics total = row_count;
  if total<>0 then raise exception 'FAIL: member deleted another author Storage'; end if;
  begin
    perform public.create_family_circle('anonymous family','אותו שם');
    raise exception 'FAIL: anonymous guest created permanent family';
  exception when insufficient_privilege then null; end;
  raise notice 'PASS: same names remain distinct; ownership/delete protections; guest cannot create circle';
end $$;

reset role;
do $$ begin
  if (select public from storage.buckets where id='moments') then raise exception 'FAIL: moments bucket is public'; end if;
  raise notice 'PASS: bucket private (Storage signed URL HTTP permission checks require Storage API integration)';
end $$;
rollback;
