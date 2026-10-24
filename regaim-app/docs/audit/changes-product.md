# Product / Home / Profile changes

Changed files (relative to project root):
- `app/src/main/java/com/regaim/app/HomeActivity.kt`
- `app/src/main/java/com/regaim/app/MemoryViews.kt`
- `app/src/main/java/com/regaim/app/Settings.kt`
- `app/src/main/java/com/regaim/app/SettingsActivity.kt`
- `app/src/main/res/layout/activity_home.xml`
- `app/src/test/java/com/regaim/app/ProfilePersistenceTest.kt`

Implemented:
- Home is a full-width calm Memory feed instead of the two-column local gallery. Actual non-duplicate shared-circle posts merge chronologically into recent local memories using real post creation times. Photos and videos use their actual media type.
- Dynamic family header uses only the explicitly configured family name, never a guessed account surname. Neutral fallback is “הבית שלנו”.
- Clear Family entry, truthful local/shared source copy, actual Circle loading/error/retry, and existing reactions/comments via SharedMemoryViews. No fake feed posts, names, counts, or AI highlights.
- FAB presents capture or existing-media import; capture remains the existing MainActivity. Empty state supports both first capture and first imported Memory.
- Event-selected/user-selected preview label comes from `previewWindow.label`; unknown-highlight memories remain cover fallbacks via the capture agent's Motion guard. Local cards show optional actual descriptions and factual person/date/place metadata.
- Anniversary hero remains an actual historical match or accurately named favorite. No latest-memory duplicate hero just to fill space. CTA opens the memory regardless of summary availability.
- Kept existing weekly story/video export. Today story has a quiet warm card and no invented narration without the connected service.
- New backwards-compatible FamilyMember model with stable ID/name/relationship/birth/photo; includes current user and supports partner, child and general relatives. Legacy children remain readable and are copied to the member model when edited. Existing Profile.kids/saveKids remain functional.
- Profile permits configuring member relationships and picking own/member photos stored locally in bounded JPEGs. Settings and recorder parameters are behind an explicit advanced-settings entry; capture logic/settings keys unchanged.
- Saving a member no longer discards typed family/parent fields. Childless families are valid in Profile.isEmpty (Welcome call site handled by root).
- Consumer privacy text accurately mentions deliberate Circle upload, optional remote understanding/illustration and face-containing frames; absolute claims that nothing uploads or no faces ever leave are removed. Stale price/storage estimates removed.
- Local preview loops stop when leaving Home. Existing animations/cards preserved; no recorder/backend rewrite.
- Home discards async/cached family data when the account or joined circle changes, and distinguishes a corrupted local index from an ordinary empty collection.
- Unknown original import dates display as addition dates and never qualify as historical anniversaries; today/week collection wording reflects this. Weekly video export requires two eligible video memories; photos still participate in stories.

Validation added: 5 persistence tests exercise real JSON serialization using isolated fake preferences: independent dynamic families, childless setup and no surname inference, legacy-child migration with birth/photo/identity retained, old child API preserving partner/self, and member rename keeping ID. The build agent runs these with the final integrated project tests; no success count is assumed here before its actual result.

Boundaries:
- Home network data is loaded only from the actual joined Circle; no successful remote test is claimed by this note.
- Names remain the legacy mapping for face recognition and historical memory tags; stable profile IDs do not silently rewrite old tags or authenticated backend identities. Same-name faces are still a limitation.
- User-owned family photos are local assets; selecting a photo does not claim face recognition or upload it.
- Photo/profile picker dialogs currently rely on the Activity lifecycle; process-death draft restoration is not implemented.
- Full compile/build/runtime evidence belongs in the main verification report. Static implementation inspection alone cannot demonstrate permissions, automatic capture, provider access or remote reaction delivery on a phone.
