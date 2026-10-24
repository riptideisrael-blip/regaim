# רגעים — Product / UI audit (baseline, before code edits)

Scope: all app screens, navigation, profile/onboarding, Memory presentation and current truth of capabilities. This is a static code audit; none of the findings below claims a device run or a live backend test.

## Product assessment

Today the product is a substantial native prototype: a foreground automatic capture mode saves event-centred clips; the local `Memory` object enriches them with title, summary, tags, people, favorite/milestone, location and user corrections. A five-tab shell, day/week stories, living thumbnails, local face names, AI search and a Supabase-based family circle are already present. It is beyond a camera demo, but the Home remains a gallery and the family story is split between the local index and a secondary social screen. A consumer launch would still expose developer setup (API keys/server connection) and needs stronger privacy, failure handling, data identity and reliability.

The achievable product is a calm family memory journal: preserve event capture, show one meaningful memory at a time with evidence-bound descriptions, make invitation and shared discussion accessible from Family and Memory Details, and let photo memories participate alongside video. The differentiator is **the few seconds the family wants to keep**, grounded in the event that caused capture and the user's later corrections, rather than another cloud library of every file.

## What exists and what it actually does

| Area | Baseline evidence | Assessment |
|---|---|---|
| Launch / nav | `AndroidManifest.xml` launcher is `HomeActivity`; `Nav.kt` HOME/FAMILY/MEMORIES/ASK/PROFILE | The requested five-tab shell and non-central camera are already real. Keep them. Tabs reorder separate Activities rather than using a NavHost. |
| Dynamic family header | `HomeActivity.header`; `Settings.kt` `Profile.homeTitle` | Family name is dynamic, with parent surname fallback and “הבית שלנו”. No fixed display family. However surname guessing can be wrong; prefer explicit family name or neutral fallback. |
| Home | `HomeActivity.render`, `fillRecent` | Hero/anniversary, recent memories, story today and weekly story/export are real UI. Recent content uses `mv.grid` / `gridCard`, not a living social feed. Family contributions are not present on Home. |
| Memory object | `MemoryStore.kt` `Memory` | Real title/description/tags/category/milestone/favorite/chosen/reason/score/people/manual corrections/date/place/trigger point. Video-centric: no media kind, highlight interval/provenance, reactions/comments/thread/audience. `name` stores a filename but presentation mainly uses `Fmt`. |
| Preview | `Motion.kt` `FRAMES=8`, `SPAN_MS=3000`, midpoint fallback | A soft loop of extracted JPEG frames (flipbook), not video autoplay. Uses trigger point if valid, midpoint if not. No story-aware AI interval selector. Do not label it AI-chosen. |
| Hero / on this day | `HomeActivity.pickHero` | Exact month/day older-year match is real. Fallbacks use accurate alternate labels (month / loved memory / latest), not an invented anniversary. Hero CTA says “צפה בסיכום” even if no summary exists. |
| Memory details | `MomentActivity.bind`, layout `activity_moment` | Real video poster/player, title/summary, tagged people, tags, map only when supplied, editing, favorite, share, trim/delete, ask. Story structure is present, but duplicate fact chips / people / tags / map / Ask create density; comments and reactions are absent here. |
| People | `PeopleActivity`, `FamilyActivity.members`, `Memory.allPeople` | Named faces and profile children are real; user-added names are merged with detected names without presentation of provenance. Family roster excludes the parent unless identified in face store, does not model partner/general relations. Name equality is the linking identity. |
| Profile | `SettingsActivity.profileSection`, `Settings.kt` Profile/Kid | Dynamic name/role/children/birthday. No general member identity, partner, relationship/photo-editing model. Account avatar can load; family avatar is face recognition photo or initial. |
| Family tab | `FamilyActivity.render` | Local people timelines plus a card linking to Circle. Existing private circle is secondary; no shared activity list in this tab. |
| Circle / family social | `CircleActivity.postCard` | Photo/video feed, title/caption/story/author/timestamp/people/place, 5 emoji reactions and comments already wired to `Circle`. No family message chat or nested memory thread. Network react/comment/delete failures are swallowed in handlers and can look successful. |
| Sharing | `MomentActivity.shareToCircle`; `ShareActivity.build` | Existing complete-circle sharing UI really trims up to 30s, edits text/style and previews. Recipient selection (person / subset / multiple circles) does not exist; do not expose a pretend audience selector. Current screen over-emphasizes trim and styling ahead of destination. |
| AI Ask | `ChatActivity`, `MomentActivity.ask` | AI chat/search and ask a specific Memory exist, conditional on Claude user API key. `ChatActivity` is not family-to-family communication. History only lives in Activity memory. Empty Ask response currently promises every laugh saved. |
| Weekly video | `HomeActivity.makeReel` → `ReelMaker.make`; `StoryActivity.week` | This is implemented, not a placeholder. Full device/export verification still needed. Keep CTA with real progress/error states and 2-memory threshold. |
| Empty/loading/error | Home empty; Moment analysis error; Circle loading/error; Ask thinking | Many real states exist, but some copy over-promises (“כל צחוק”, “שום דבר לא עולה לאינטרנט”), indefinite pending label can follow failure, and silent social failures need fixing. |
| Visual language | `colors.xml`, `MemoryViews`, shared `Ui` | Warm off-white, ink, purple, rounded media, gentle fade/heart micro-interaction: sound foundation. Mixed emoji, multiple mini cards, dark-purple AI card, bubbles/grid and lots of settings leave an inconsistent journal feel. |
| Motion | `MemoryViews.reveal`, `heart`, `Motion`; `MomentActivity` transitions | Gentle reveal and heart animation implemented. Opening memory slides upward; it is not a shared-element expansion. Motion checks attached-to-window rather than actually visible/lifecycle foreground; bitmap cache key omits extraction width; frame generation is unbounded on disk. |
| Technical profile | `SettingsActivity.render` always calls triggers/camera/people/AI/learning/privacy | Consumer Profile contains recording parameters, sensitivity percentages, provider keys, repeated analysis/cost, reset controls; move behind clear advanced configuration without rewriting capture. |

## Five biggest problems

1. The core story is fragmented: Home is a grid of local clips, Family is face timelines, and social memories/comments are isolated in Circle. The family feedback loop is difficult to discover.
2. Highlight quality is a capture-trigger approximation, not meaningful-scene scoring. `highlightMs` cannot express intervals or method/confidence. Unknown midpoint must remain an honest fallback.
3. Consumer setup depends on supplying Claude/Gemini and potentially Supabase infrastructure details. This is a developer prototype flow and the main commercial adoption barrier.
4. Profile/face/social identities use names rather than stable shared member identities. Two people with the same name, renamed children, general relatives and partners are not handled as a coherent family model.
5. Reliability/privacy presentation falls short of the product promise: JSON read errors silently become “no memories”, several network mutations swallow errors, and privacy copy omits explicit circle upload while saying local-only. Device permissions/background capture must be tested, not inferred.

## Supporting screens and layout inventory

- `MainActivity` / `activity_main`: operational capture screen with run/stop, manual save, zoom, live bubble, latest saved bubbles, story text and diagnostics/sensitivity controls. This is a mode entered from Home and Profile; keep its recording operations intact and keep diagnostics secondary. It is not the launcher.
- `TrimActivity` / `activity_trim`: real video/range editor with thumbnails, segment preview, check-the-end action and save/error feedback. It changes stored video, so retain explicit user action rather than using it as a fake AI highlight selection.
- `ComicActivity` / `activity_comic`: real options/generation/variant selection/save/share UI. Some illustration depends on Gemini while captions/story depend on Claude; this is an optional creative tool and should not displace Memory, people or conversation in the core loop. Provider text/cost promises require truthfulness.
- `MilestonesActivity` / `activity_milestones` / `item_milestone`: actual local memories with nonempty milestone, chronological rows, actual title/date/description; empty visibility is based on real records. Keep accessible from archive/family without requiring every Memory to be a milestone.
- `LearnedActivity` / shared `activity_screen`: actual stored preference insights, supporting Memory links, explicit user correction, exclusion and reset. An unusually useful transparency feature, but extensive counts and trigger diagnostics belong behind Profile's additional settings.
- `StoriesActivity` / shared `activity_screen`: actual `Topics` collections with cover strips and topic playback. `StoryActivity` is built programmatically, plays the selected memories with music and timed slides. It is an existing reliving feature, not a placeholder; it previously treated all Memory sources as video and needed photo-aware behavior when photo import was added.
- `PeopleActivity` / `activity_people`: named and unnamed face-group grid, scan status, rename/forget and open person's memories. Its faces are evidence from local face scan, while Profile relationships are user-entered family context; keep that distinction.
- `activity_home`, `activity_memories`, `activity_moment`, `activity_chat`, shared `activity_screen` and `bottom_nav` establish the principal shell. `item_chip`, `item_bubble`, `item_day_header` and `item_milestone` are the reused archive/list components. Several screens use dynamically built LinearLayouts rather than XML-native RecyclerView virtualization. RTL is intentionally configured, but image touch targets, large fonts/system insets and device lifecycle still require runtime verification.

## Five biggest opportunities

1. Existing automatic capture and event point supply a credible event-centred preview immediately; later improve ranking by combining audio/face/motion and user feedback without changing recorder.
2. The already wired Circle can become the main Family experience and the discussion action inside Memory. No need to replace it.
3. A full-width vertical Home feed with one emotional anniversary hero and clear “only on this phone / shared with circle” provenance can change the product feel quickly using existing components.
4. User corrections, favorites, milestones, named faces and taste learning provide unusually valuable personal meaning signals; make correction accessible and retain source evidence.
5. Real weekly export, topic stories and anniversary resurfacing offer a family ritual and durable value beyond initial recording novelty.

## Incremental MVP / screens

| Screen | Recommended next implementation |
|---|---|
| Home | Dynamic explicit family title, quiet subtitle, a Family-circle entry and one primary capture/import action. True full-width vertical Memory cards: photo or event preview, human title/time, factual people/place only, optional actual summary, private/shared provenance, favorite + share + open-discussion. Anniversary hero only on actual historical match; neutral recent hero otherwise. Week below feed, real export state. |
| Memory feed | Local memories now; optionally a small separately labeled shared-circle section from real backend results. Never show invented activity/counts. Reuse existing Memory/Circle models and IDs. Source/media/preview type must be explicit internally. |
| Memory Details | Media → title/date → actual summary (“מה קרה ברגע הזה?”) → confirmed/user-tagged people → optional place/tags/milestone → private sharing and real circle conversation. Collapse metadata/Ask extras. Keep edit, trim and delete as quiet overflow actions. |
| Family | Header from configured name, members with stored names/relations/photos, primary real Circle card/feed, invitation action, shared memories with reactions/comments. Person timelines secondary. No status unless sourced. |
| Family Circle | Preserve create/join/code/web invitation/backend. Improve warm empty/loading/error/retry and make upload destination explicit. Surface prototype limitation honestly when server is not configured. |
| Sharing | First show the real audience: the existing circle. Default meaningful interval using actual preview range. Text optional; trim/style behind edit actions. If not joined, create/join. Subset-recipient sharing only after real authorization data model exists. |
| Comments | Show actual backend comments in a Memory discussion view linked by `memoryId` / shared post ID. Add comment input; visible sending/failure/retry; no fake optimistic counts. |
| Chat | Distinguish AI Ask from family communication. MVP can expose a **memory discussion** via existing comments; standalone family chat should wait until real member-authenticated message persistence/permissions, unread state and delivery errors are implemented. Do not label comments a fully implemented group messenger. |
| Memories | Keep existing RecyclerView library for chronological browsing/filter/favorites/months. Use covers consistently and retain bubbles as capture metaphor only if tested; archive is where gallery density fits. |
| Ask | Helpful dynamic prompts using actual existing people/tags. No mandatory key popup on entering; explanatory state with Configure action. Evidence-only answers, unknown when absent; distinction between recorded time and inferred story. |
| Profile | Account, configured family members (parent/partner/child/relative with relation), Family-circle access, privacy summary. Camera/triggers/keys/reanalysis/reset under advanced settings. No fixed personal example names. |

Core loop: user starts capture → existing detectors trigger event-centred save → optional actual AI understanding → Memory record + honest event/fallback preview → Home resurfacing → explicit circle share → real reaction/comment → return to Memory conversation. Photo import follows the same Memory → feed → share loop without claiming video analysis or trigger selection.

## Change now / defer

Change now: vertical Home, dynamic general members and neutral form copy, truthful preview/analysis/photo states, make Circle central, surface real comment discussion, social error handling, explicit private/shared destination, consumer Profile separation, preserve existing capture and database schema compatibility.

Defer: public social mechanics entirely; advanced story-aware highlight AI until there are labeled examples and quality criteria; recipient subsets/multiple circles until backend authorization supports them; standalone family chat until durable authenticated messages exist; fully automatic cloud backup; shared-element video expansion; embeddings/server AI orchestration; broad architectural rewrite. Existing weekly export should be verified rather than hidden as “future”.

## Concrete issues to fix without rewrite

- `Profile.isEmpty` ignores family name; Welcome decides “first setup” when there are no children. Generic families without children should not be treated as incomplete.
- `SettingsActivity.editKid` calls `render` after modal save without first persisting the newly typed family/parent fields; those edits can be lost.
- Neutralize personal examples in `SettingsActivity`, `PeopleActivity`, `MomentActivity`, `CircleActivity`, `LearnedActivity`. They are hints, not actual family data, but still violate the user's strict UI request.
- `HomeActivity` rerenders all children when count changes; old scrolling/motion state churns. Preserve feed scroll and avoid repeated reveal for loaded entries.
- `MemoryViews.hero` currently displays “צפה בסיכום” even when summary is absent. Use “פתחו את הזיכרון”.
- `MemoryViews` always shows video duration badges. Add true photo branching before photo import is exposed.
- `MomentActivity.bindInfo` shows “ללא מיקום” despite the requirement to hide unknown metadata. Keep optional facts optional.
- `CircleActivity` react/comment/delete catches ignore exceptions. Make failures visible and keep drafted text.
- `strings.xml tips` says nothing goes online; `SettingsActivity.privacySection` does not mention Circle uploads. Update to real optional AI frame sharing and deliberate family uploads; extracted frames can contain faces even when embeddings stay local.
- `Motion` claims “only while on screen” but only checks attachment. Stop preview on Activity pause and offscreen cards; honor motion preference.
- Profile/Family linking by equal names is limited. A backwards-compatible general member model is a step forward, but does not replace authenticated backend identities.
- Most UI strings are Kotlin literals, fonts are fixed Light portrait and minimum touch targets are sometimes below 48dp. Accessibility/RTL device verification remains necessary.

## Validation boundary

This audit proves implementation paths exist, not that capture runs on a given phone, that provider credentials/configuration are valid, that Supabase is reachable, or that remote storage is private. Full compile/build is required and must report infrastructure blockers precisely. Navigation and state checks need actual emulator/device run where available. Do not claim camera/AI/social runtime regression pass from compile alone.
