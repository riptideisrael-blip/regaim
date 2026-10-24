# Family/social changes delivered

## Files

Changed `Account.kt`, `Circle.kt`, `CircleActivity.kt`, `FamilyActivity.kt`, `ShareActivity.kt`, `web/index.html`.
Added `FamilyChatActivity.kt`, `SharedMemoryViews.kt`, `server/secure-family-circle-v3.sql`, `server/secure-family-v3-tests.sql`, `server/SECURE-FAMILY-DEPLOYMENT.md`, `web/family-client-tests.cjs`.
Root owns manifest/FileProvider declarations. Product agent owns Profile.members model consumed here.

## Implemented behavior

- Preserve existing circle create/join/invite/share/edit/delete, four reactions and comments concept while replacing client-side pseudo-privacy with authenticated v3 contracts.
- Immutable account author IDs; distinct reaction identities for same-name members; no ownership by mutable names.
- Exact post lookup by local memory ID and post ID, not limited to latest 60 feed posts. Memory-specific reaction/comment access contracts for root integration.
- Authenticated membership + server version check before social feed/sharing; no anonymous fallback after auth failure, no prototype schema/data-loss fallback.
- Private Storage object path persistence, 15-minute signed read links; authenticated uploads under circle/user path; documented correct bucket DELETE prefixes API; failed new memory sharing attempts clean uploaded assets best-effort.
- Photos and videos share with truthful media kinds; existing video remux/range implementation retained. Imported media without known capture date shares taken_at=null; shared date labeled as share time. Neutral stored-description provenance.
- Family tab now shows local configured relatives/relationships (including self/partner via Product model), actual approved server members, three real shared posts, real persistent family chat and existing circle link.
- FamilyChatActivity is human messaging, separate from AI Ask. Family-wide and memory-linked conversations persist to family_messages; send failures retain text; saved draft on recreation; 10-second polling and 100-message window acknowledged.
- SharedMemoryViews reusable Home/Family real post cards with cover/media/reactions/comments/conversation. Image/source scope matches current account/circle; no fake remote auto-preview or AI highlight claims.
- Circle error states now expose comment/reaction/delete failures; invalid old invites/missing migration/auth yield readable messages. Invite rotation owner-only, warning that previous links stop adding people.
- Circle and Family cached remote content clears on account/circle change. Logout removes circle identity, shared markers and disk/memory media caches. Shared markers scope account+circle+memory.
- Browser invite flow remains no-install: anonymous Auth establishes immutable ID, RPC redeems 128-bit invite; authenticated REST and signed Storage; real family/memory messages UI. Disabled anon Auth fails explicitly. Text safely appended via DOM, no HTML injection.

## Server protections and deployment boundary

Opt-in SQL migration retains old content but revokes six-character invitation credentials and flips bucket to private. No first-claim ownership of old circles. Verified administrator must map legacy families and optional post/media ownership to Auth IDs. Mapping post author after migration requires reviewed administrator transaction temporarily disabling/re-enabling identity trigger; explicitly documented. Unknown legacy authors cannot edit/delete through client. Restrictive membership/author policies prevent custom permissive policies reopening access. Only permanent Auth accounts create circles; anonymous Auth guests redeem valid invitation.

Nothing has been deployed to the user's Supabase project or static web host. Until deployed with auth enabled and verified legacy mappings, remote feed/sharing/chat is intentionally unavailable with explicit errors. Claim source-complete conditional integration, not a live operational private service.

## Validation actually performed

- `node --check` extracted web script: pass.
- `node web/family-client-tests.cjs`: PASS 9 offline Auth/invite/media/reaction/comment/thread contracts. Mocked HTTP only; no live posts/messages/uploads/auth created.
- SQL fixtures provided for disposable local Supabase; not executed by this agent because no PostgreSQL binary initially available. Parent may install/validate and report separately.
- Build/Android lint handled centrally by root/build agent. Initial integrated debug/release Kotlin compile passed; final lint fixes in CircleActivity explicit braces landed before final rerun. Final result authoritative in parent report.

## Remaining product/engineering limitations

- No person-specific recipient ACL/sharing UI, no server/local profile roster synchronization or full member-removal UI. Leaving circle clears local state, does not revoke backend membership; removal remains trusted-admin operation.
- Guest identity persists only in that browser; clearing storage requires valid invitation and creates a new guest identity. Auth tokens remain standard private prefs/localStorage; Google existing implicit OAuth lacks PKCE/state hardening.
- No offline message outbox, attachments in chat, read receipts, push notifications, conversation pagination beyond latest 100, comments replies/edit UI, family events or real activity log. Actual social feed uses posts with current reactions/comments.
- Feed still latest60; exact memory/post fetch fixes detail access beyond that window. Private signed links bearer-accessible by anyone holding that temporary link until expiry; no end-to-end encryption claim.
- Ownership/Storage policy runtime integration not verified against live service. Legacy Storage schema compatibility owner_id requires test Supabase deployment.
- Native remote media opens external viewer, no remote calm auto-playing highlight. Local memory preview is handled by root/capture agent.
- Partial upload cleanup remains best-effort; robust resumable upload/outbox reconciliation and orphan job remain next steps. Shared styles affect cover/UI, not transformed video pixels.
- On-device memories/profile remain installation data across account switches; remote caches/circle identity now isolated. Full per-account local library separation is a future product decision.
