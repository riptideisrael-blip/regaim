# Secure family sharing v3 deployment

This release includes source implementation, an opt-in SQL migration, and tests. Nothing was run on the live Supabase project. Android still supports on-device capture/memories without an account. Remote sharing and family chat now require verified authentication and server v3; there is deliberately no fallback to the unsafe prototype policies.

## Before deployment

1. Back up Database and Storage. Test a restored copy first. Keep the supplied original schema as historical prototype setup only; do not rerun it after v3 because it reinstalls permissive policies and a public bucket.
2. Existing project must already have the base circles/posts/reactions/comments tables. New test project: run base setup, then immediately apply v3 before putting real family data into it.
3. Verify email/Google Auth configuration and `moments://auth` redirect. Android remote sharing uses permanent email/Google accounts; Google provider must be enabled. Web invite visitors use anonymous Auth users with immutable IDs; enable Anonymous Sign-ins and appropriate CAPTCHA/rate limits on your service. Disabled anonymous Auth produces an explicit web error, not public access.
4. Upgrade Android and web together. v3 makes `moments` private, revokes old six-character links and blocks legacy anonymous clients. No data rows or media objects are deleted by the migration. Old public URLs are converted to object paths by the new clients before generating short-lived signed links.

## Migration and verified legacy ownership

Apply `secure-family-circle-v3.sql` as the trusted database administrator. It is rerunnable and rotates existing short codes only on first installation. pgcrypto may live in any existing extension schema; token generation discovers it without relocating the extension. All new invites contain 128 random bits, expire after 90 days, and can be rotated by the family owner. Existing members remain members when the invitation is rotated.

There is no “first person who knows an old code becomes owner” rule. An administrator must explicitly verify which Auth user owns each existing circle. Inspect the circle IDs and Auth user IDs privately, then substitute verified IDs into a transaction like this; do not paste arbitrary IDs from an unverified user:

```sql
begin;
update public.circles set owner_id = '<VERIFIED_OWNER_AUTH_UUID>' where id = '<VERIFIED_CIRCLE_UUID>';
insert into public.family_memberships(circle_id,user_id,display_name,relationship)
values('<VERIFIED_CIRCLE_UUID>','<VERIFIED_OWNER_AUTH_UUID>','<OWNER_DISPLAY_NAME>','מנהל/ת המעגל')
on conflict(circle_id,user_id) do nothing;
-- Optional: map ONLY legacy posts whose author was independently verified.
-- The identity trigger deliberately blocks app ownership changes. Only the
-- trusted administrator temporarily disables it for a reviewed backfill.
alter table public.posts disable trigger family_post_identity;
update public.posts set author_id='<VERIFIED_AUTHOR_AUTH_UUID>'
where id in ('<VERIFIED_LEGACY_POST_UUID>') and author_id is null;
alter table public.posts enable trigger family_post_identity;
-- Optional Storage ownership backfill for verified legacy file paths, needed
-- if their original uploader should delete them through the application:
update storage.objects set owner_id='<VERIFIED_AUTHOR_AUTH_UUID>'
where bucket_id='moments' and name in ('<VERIFIED_LEGACY_OBJECT_PATH>');
commit;
```

Do not infer ownership from a display name; names are mutable and may collide. Legacy rows with null author IDs remain readable to approved family members but cannot be edited/deleted by a client. All members receive an explicit immutable Auth identity; local profile people and circle account members are distinct concepts. Optional approved relatives can be seeded as memberships or can redeem the newly issued invite.

The owner signs in, opens the mapped circle, and shares the new invitation. People can join through the web with no app install. Anonymous web users lose their browser identity if they clear local data; they must reopen a valid invitation. This is a tradeoff of guest access, not a verified personal identity.

## Permissions implemented

* `circles` and `family_memberships`: only approved members can read; clients cannot directly create/alter memberships or owner IDs.
* `posts`: member read, own immutable Auth author insert/update/delete. Post family/author cannot change through app edits.
* `reactions/comments`: membership read and own author write/delete. Reaction identity uses Auth ID, so two people with the same name can react independently.
* `family_messages`: membership read, own author insert/delete; optional linked post must belong to the same circle. Persistent family chat and memory conversations are separate from AI Ask. Clients load at most 100 recent messages.
* `moments` Storage: private bucket, member read/sign access, uploads under `circleId/userId/file`; only uploader deletes. Existing stored public URLs are paths after parsing and require membership to sign.
* Every issuing RPC requires an authenticated Auth user. A publishable key alone grants no family access. Owner can rotate invitations; leaving a circle in UI clears local state but does not revoke server membership. Removing an existing member currently requires administrator action.

Signed media links expire after 15 minutes and can be viewed by whoever possesses that particular temporary URL. Issuing them requires membership. They are not end-to-end encrypted, and this release does not claim end-to-end encryption. Server-mediated individual-recipient sharing, robust member-removal UI, per-memory ACLs, offline send queue, mobile identity isolation and permanent guest-account upgrade are next steps.

## Validate before release

Run `secure-family-v3-tests.sql` against a disposable Supabase database, never customer production. The script rolls back fixtures and asserts anonymous deny, cross-family read deny, spoofed author deny, same-name identity separation, cross-family message-post deny, own writes, and non-author edit/delete/Storage deny. SQL parser validation is not a substitute for running these policies under authenticated roles.

With Storage/Auth APIs running, also test: unauthenticated public media URL rejects; family A member can sign/read A, cannot sign/read B; expired/revoked invitation cannot add a new user; existing approved members remain after invite rotation; logout clears local circle and media cache; disabled anonymous Auth shows explicit failure. Run Android on real hardware for navigation, media and capture regression checks.

## Operational limitations

Backend source has not been deployed or verified against the current account/service. Offline chat sends preserve typed text and surface failure, but there is no outbox. Android polls conversations every 10 seconds, web every 30 seconds. Membership relationships/avatars in server roster are independent of local configured relatives, with no synchronization yet. There is no recipient-specific share selector because its access control is not implemented.
