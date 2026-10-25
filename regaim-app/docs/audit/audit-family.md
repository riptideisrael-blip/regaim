# Family, sharing, account and privacy audit

Audit of supplied source; no live backend reads/writes, no runtime claims. Root: `project/regaim-app`. No secrets reproduced. The bundled Supabase publishable key is public configuration, not a service credential; the unsafe policies are the privacy failure.

## Implemented capabilities and actual scope

| Area | Actual implementation | Backend/local | Limits |
|---|---|---|---|
| Email sign up/sign in | Real HTTP calls to Supabase Auth; confirmation state handled; user metadata parsed | Backend auth; session/user cached in ordinary SharedPreferences | Not verified against deployed auth configuration; no password reset/delete-account workflow |
| Google login | Browser authorize URL + `moments://auth` implicit token redirect | Backend auth + Android deep link | No PKCE/state binding; configuration must be enabled in Supabase |
| Dynamic family name | `Profile.familyName`, `parentName`, `homeTitle` | SharedPreferences | Parents/children are local to installation, not cloud family records |
| User/children | Name, role, children names/birth dates; Google account avatar | SharedPreferences | No partner model, arbitrary relatives/relationships, profile photo editor, stable person IDs |
| Family screen | Children plus manually named face groups; per-person local memories | JSON/local face store | Does not load real circle member roster or family activity |
| Circle create/join | Real PostgREST circles insert/query using 6-character code | Supabase | Joining only caches circle ID/code/name; no membership record or authorization |
| Invite link | Circle code + base64 URL/public key; web app reads same server | Share intent/web | Permanent share credential; no expiry, revocation, invitation acceptance identity |
| Shared feed | Posts with media/story/place/people/caption/style; reactions/comments | Supabase DB/Storage | 60-post limit; Android refresh on resume/manual/action, web polls 30s; no pagination/offline feed |
| Memory share | Keyframe-copy cropped MP4 plus cover, title, optional summary/caption/style | Supabase DB/Storage | One selected circle; no individual recipient selection or per-memory ACL |
| Gallery share | Upload original photo/video and create post | Supabase DB/Storage | Android picker accepts `*/*`; current upload assumes extension mp4/jpg without conversion; current time, not original capture time |
| Reactions | Four emoji insert/delete with `(post_id, member, emoji)` unique | Supabase | Identity is mutable display name; simultaneous action conflict errors swallowed on Android |
| Comments | Plain chronological list per post; add via dialog | Supabase | No reply IDs, edit/delete comment UI, stable author identity, timestamps in Android cards |
| Family chat | **Absent** | — | No conversations/messages tables, repository, or human-chat screen |
| AI Ask | `ChatActivity` and `ClaudeClient.ask`; one-memory Ask also exists | Anthropic + local memory list | Chat history only Activity memory; not family messaging; all memory metadata sent per global Ask |
| Share edit/delete | Real PATCH/DELETE plus attempts to remove Storage | Supabase | UI ownership check is display-name equality; server does not enforce ownership |

## Evidence

* `Account.kt:77–89`: sign up and password sign in. `Account.kt:93–110`: authorize and implicit callback. `Account.kt:113–145`: persistence/token refresh. `Account.kt:151–154`: logout only clears auth fields.
* `Settings.kt:10`: regular MODE_PRIVATE SharedPreferences, containing session tokens and API keys via caller settings. Android manifest `allowBackup=false` is positive but does not encrypt preferences.
* `Settings.kt:41–75`: local family/parent/children data and dynamic title. `SettingsActivity.kt:222–247`: role and child forms; no partner/relative model. Static sample names remain in UI hints (`SettingsActivity.kt:227,229`, `CircleActivity.kt:437`), despite no hardcoded actual family data.
* `FamilyActivity.kt:41–45`: members = children plus named face store. Parent profile is not included unless present in named face store. `FamilyActivity.kt:53–59`: circle is a link card, not embedded family activity feed.
* `Circle.kt:206–225`: create/join only inserts/looks up circles and saves preferences. No membership insert. `Circle.kt:117–120`: invite parameters.
* `Circle.kt:229–254`: real posts/reactions/comments REST calls. `Circle.kt:259–266`: mutable text name is reaction/comment identity.
* `Circle.kt:307–323`: shareMoment upload pipeline and DB post insert. `Circle.kt:332–354`: update pipeline. `Circle.kt:375–404`: gallery uploads. `Circle.kt:428–501`: actual remux trim range. Trim seeks previous keyframe, so exported start can be earlier than selected range; no re-encode.
* `CircleActivity.kt:269–274,319`: edit/delete eligibility is `p.author == Circle.me`. `CircleActivity.kt:306–309,334–336,428–430`: exceptions silently swallowed for react/delete/comment. `CircleActivity.kt:408–413`: shared media opens external viewer, not a memory story page.
* `CircleActivity.kt:88`: prototype label; `CircleActivity.kt:173–181`: mostly code/identity/control header, several action rows; comments all rendered directly below posts at `315–317`.
* `ShareActivity.kt:112–174`: real video range slider/playback preview, anchored to existing `m.highlight`. `ShareActivity.kt:230–236`: one-circle send without recipient choice. `ShareActivity.kt:252–253`: developer-facing schema update hint. Style filters only change cover/web presentation, not exported video pixels.
* `MomentActivity.kt:614–627`: existing share-to-circle entry must be preserved. Local memory Details has no comments/reactions retrieval; social content resides in separate Circle screen.
* `ChatActivity.kt:24,33,124–128`: explicitly AI memory search and ephemeral history. `ClaudeClient.kt:253–290`: metadata retrieval prompt to Anthropic, not family communication.
* `Nav.kt:11–28`: desired five tabs already exist: Home, Family, Memories, Ask, Profile; camera already a mode.
* `server/family-circle-setup.sql:5–42`: real circles/posts/reactions/comments schema, no members/messages/invites/owner columns. `server/update-2-caption-style.sql`: caption/style/memory_id extension.
* `web/index.html:128–148`: REST and Storage with public key; `180–194`: circle query then feed. `277–305`: reactions/comments and local display-name identity. `331–350`: web gallery post uploads. Text inserted via `textContent`/DOM nodes (`163–167,258–259`), so standard text XSS is avoided.

## Critical commercial/privacy blocker

The shipped SQL enables RLS but then gives **all CRUD** to `anon` and `authenticated`, unconditionally: `family-circle-setup.sql:53–56`, `using (true) with check (true)`. The code is only a client-side query filter. A client with the bundled public key can select every circle without a code, then read/alter/delete posts/comments/reactions across families. UI author checks provide no server protection. This is more permissive than the source comments' “anyone who has the circle link” warning.

Storage bucket is **public** (`sql:59–61`), read/upload/delete policies cover the entire bucket (`66–68`). Uploaded links remain publicly accessible by URL; exit circle/logout is not revocation. `Circle.upload:180` returns public URLs. A public bucket is incompatible with promising “only your family can see” for consumer users. The default application points all users at the same prototype Supabase project.

Auth existence does not fix this: `Circle.open:132–134` supplies a valid account bearer where available but falls back to anonymous access. `Account.token:145–146` silently returns null after refresh failure, which unintentionally downgrades account requests to anonymous. No `auth.uid()` policy exists. Display-name identities also collide; two people choosing “אבא” share reaction/ownership identity.

Recommended prerequisite, preserving current Circle UX and content: add owner/member/invite identity schema; authenticated RLS by membership and immutable author IDs; private Storage with path membership policies and expiring signed URLs; revocable single-use or time-limited invite redemption. Do not automatically execute migrations on the real service. Ship migration/integration instructions and explicitly report deployment required. An anonymous prototype can remain only with conspicuous accurate disclosure and no private-family guarantee.

## Additional risks and defects

1. **Logout does not remove circle access or profile.** `Account.signOut` removes account fields only; `Circle.joined` ignores auth (`Circle.kt:107`). Profile logout dialogue says circle will work again after sign in (`SettingsActivity.kt:179`) but prototype circle still works anonymously. A different account on this installation inherits the previous family, people, memories, circle and share identity.
2. **Shared-state cache is wrong scope.** `circle_shared_<memoryId>` (`Circle.kt:322,372`) is neither per-circle nor per-account, has no post ID and never syncs with server. Leaving/joining another circle leaves old memories marked shared. Cannot reliably map Memory Details to shared post/thread.
3. **Partial uploads orphan media.** share uploads video then cover then DB (`307–321`); failed cover/DB only deletes local temp (`323–324`), leaving public objects. Update may upload new assets then fail PATCH. Delete exceptions in Storage are ignored (`277–285`), so deleted content may remain public.
4. **Cover replacement bug.** `updatePost` sets `oldCover=post.cover` whenever recutting (`343–346`), even if coverAt returned null and no new cover was put. Then deletes old cover after PATCH (`354`), breaking post cover URL.
5. **Gallery accepts nonmedia.** Android GetContent launched `*/*` (`CircleActivity.kt:178`); `shareFromGallery` treats every nonvideo MIME as photo and uploads original bytes as `.jpg`. Must restrict picker and validate supported image/video MIME before upload. Web `accept` is only client hint; validate on server too.
6. **Gallery captured-date loss.** Android sets taken_at=current time (`Circle.kt:402`). Web file.lastModified is not reliable capture date (`web:349`). Use EXIF/video capture date when available; preserve truthful fallback wording.
7. **Secret-key validation incomplete.** `configured` rejects only `sb_secret` prefix (`Circle.kt:106`), not legacy service-role JWT; no service credential should ever be stored in client/invite. Validate public project configuration outside UI; no need to echo keys.
8. **No offline queue or stable loading/error states.** Family screen is local; circle Android load may start after render and not show spinner immediately; comment/reaction errors swallowed. Share errors surfaced but no retry queue/transaction reconciliation.
9. **Media cache persists after logout/leave.** Circle disk cache under cacheDir (`Circle.kt:514`) and static LruCache (`506`) are not cleared. With private signed URLs, load through auth-aware repository, invalidate on account change and use robust cache keys. URL hashCode-only filenames can collide.
10. **Google callback lacks PKCE/state.** Authorize request (`Account.kt:93–94`) and redirect (`97–110`) have no flow nonce/verifier; receiver checks scheme only (`WelcomeActivity.kt:73`). Tokens stored before server user validation; malformed callback can overwrite good local session. Use SDK/PKCE + verified App Links when preparing consumer auth.
11. **Too much context on every global Ask.** `ClaudeClient.ask` sends the complete local memory metadata list with family names/profile; no bounded index/retrieval or summary limit. This needs clear disclosure and later server mediation/key protection. Unknown-answer instructions do exist (`275–277`); grounded-answer behavior is not runtime verified.

## Incremental product and engineering changes

**Safe now without rewriting capture/AI:** remove fixed sample-person hints; make Family show configured family name and role; promote Circle activity and existing social affordances; add truthful member/activity distinction; improve errors/loading and media MIME validation; preserve existing range selector/remux pipeline; add honest privacy message before sharing; improve on-device profile relationships with versioned JSON and stable IDs; add post-thread UI using existing comments rather than inventing chat.

**Do after backend identity prerequisite:** optional memberships roster, per-memory reactions/comments in Details, secure recipient selection, conversation schema (circle thread and memory-linked thread), auth/signed URL integration. Family chat is new work requiring DB, policies, client repository, send/retry/error/offline states and web compatibility. A mere empty Chat UI must be labeled unavailable and is not completion.

**Do not prioritize:** public-network features, engagement counters, style proliferation, rewriting the recorder, replacing the five-tab navigation.

## Validation boundary

Static trace confirms actual client and SQL implementations. Did not authenticate, read user data, send messages, create circles, upload/delete media, deploy SQL, or exercise device UI. “Works” must be stated as implementation exists and needs server/device integration verification, not verified production functionality.

## Primary references checked for planned secure increment

Supabase official docs checked during implementation planning: Anonymous Sign-ins (`https://supabase.com/docs/guides/auth/auth-anonymous`), Row Level Security (`https://supabase.com/docs/guides/database/postgres/row-level-security`), and Storage signed URLs (`https://supabase.com/docs/reference/javascript/file-buckets-createsignedurl`). Anonymous Auth users have immutable user IDs and use authenticated role; the public anon key does not constitute a user. Signed URLs grant bearer access only for limited lifetime; membership still must control issuing them. These are implementation references, not evidence that the supplied deployment uses these protections.
