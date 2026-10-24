# Capture / media / AI implementation notes

## Implemented without replacing capture

Changed source: MemoryStore.kt, Covers.kt, Motion.kt, ClaudeClient.kt, ClipSaver.kt (ClipTrimmer only), ReelMaker.kt, ComicMaker.kt, StoryActivity.kt.
New source: HighlightPolicy.kt, MediaImporter.kt, MediaImportActivity.kt.
Tests: HighlightPolicyTest.kt (13 tests), MemoryAnalysisMergeTest.kt (11 tests).

- Memory JSON adds backward-compatible defaults for mediaType (video/image), selected highlight range, source, imported-time provenance, narrative authorship/provenance and origin. Legacy capture marker highlightMs is retained unchanged.
- PreviewPolicy implements only user/trigger/fallback provenance. Explicit user intervals are valid only within media bounds and 1–6 seconds. Trigger previews are clamped 4-second windows. A missing/out-of-range marker uses a static cover fallback, never fake semantic AI selection. Images never have video preview intervals.
- MediaImportActivity uses Android OpenDocument with image/video MIME choice and retained read grant. It imports only the selected file and does not upload/share automatically. Optional user title is preserved against automatic AI replacement. Original photo/video files remain intact. Filename is stored privately but not used as display title.
- Known MediaStore DATE_TAKEN becomes capture date; if absent, timeSource=imported and time means addition date. No guessed location or family identity is added. Consumer UI must disclose addition date accordingly.
- MediaImages bounds large-photo decoding and handles image orientation (ImageDecoder API28+, BitmapFactory/EXIF API26–27). Photos work in covers, cloud summary sampling, face sampling, comics and Story slides.
- Covers now target chosen/detected interval center rather than always video midpoint; cache includes URI and preview interval to invalidate after edits/trim. Image cards use actual selected image.
- Motion remains an eight-frame silent crossfade flipbook, not encoded video autoplay. It now samples the actual selected/detected range, refuses unknown fallbacks, and pauses transitions while offscreen or Activity is not resumed. No detector changes.
- StoryActivity shows imported photos and unknown-highlight videos as static media slides. Known marker/user preview plays only its bounded interval. Existing navigation/tap/hold/music story behavior remains.
- Claude prompts correctly distinguish photo versus video samples, prohibit audible quotation/speaker/end-of-video/first-in-life claims based only on stills, restrict named identities to recognized/user-labelled memory people, and explicitly disclose unknown original capture dates. New descriptions carry sampled_images provenance. Empty malformed descriptions fail and remain retryable instead of showing raw JSON. AI does not auto-create milestone claims from stills; old/user-marked milestones remain.
- Analysis merges only its own fields into a fresh Memory. User titles/descriptions and changes during the request survive. Responses for outdated media/identity/place/date context are rejected and remain pending for retry; user-authored summaries are kept.
- Local index mutations require a successful strict JSON read, preventing corrupted index overwrite. Read failure sets a clear error state; successful recovery clears only that exact error. Temp-file rename failure is no longer silently treated as success. Full recovery/backup/durable database migration remains future work.
- ClipTrimmer uses the actual previous keyframe base and measured output duration for markers/selected range. A cut-out marker becomes unknown, not fabricated at an edge. Regenerates thumbnail. Resources/temp files/pending MediaStore items are cleaned on failure. User-imported originals are never deleted; older Android edits use private edited_media files. Original capture-saving section is unchanged.
- ReelMaker exports videos only and validates video codec data, audio codec/sample rate/channels and orientation before remuxing into a common track. Explicit chosen intervals are respected; legacy marker/midpoint behavior remains for other clips. Incompatible files are skipped and export requires at least two usable clips. This is an actual implementation, not placeholder, but captions/music/transitions/re-encoding are still absent.

## Concrete preserved-source verification

Compared uploaded ZIP with working source byte-for-byte: CaptureService.kt, Core.kt, LaughDetector.kt, KeywordSpotter.kt, FaceWatcher.kt, People.kt, LiveBubbleView.kt and Preview.kt are unchanged. The original ClipSaver object is byte-for-byte unchanged; only the following ClipTrimmer implementation and its documentation changed.

## Honest limits

No semantic story-based AI highlight scoring was implemented. There is still no saved audio transcript, detector signal timeline, full-video understanding or embedding search. On-device YAMNet/face/speech runtime quality needs physical Android device testing. Authentication/API availability and family backend policies need live staging validation. Capture uses existing in-memory 50-second buffers, existing foreground-service scheduling and existing encoder timestamp design; no claim of process-death/all-day capture reliability was added.

New image paths and SAF grants require device tests (including cloud-document/offline permissions and API26 orientation). Preview is a flipbook. Imported source files can later become unavailable outside the app; retaining a URI grant does not create a backup. Local memory JSON remains phone-local. Cached media quota/cleanup, index backups, durable WorkManager queues, long-term family backup and calibrated face identity remain next-stage work.

## Verification status

Build agent owns actual Gradle debug/release compile, APK packaging, lint and JUnit execution. At handoff, current sources/tests are frozen and final build rerun requested. Earlier intermediate build compiled debug/release without media/AI source errors. Do not treat compile success as proof of Android hardware, UI flow, speech, API or end-to-end family runtime behavior.
