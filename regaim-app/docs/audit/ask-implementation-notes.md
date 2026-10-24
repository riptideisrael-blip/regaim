# Ask experience implementation

Changed source files:
- `ChatActivity.kt`
- new `MemoryFacts.kt`
- `PeopleActivity.kt` (name entry hint only)
- `LearnedActivity.kt` (personal rule hint only)
- new `MemoryFactsTest.kt`

Implemented:
- Removed automatic API-key modal on opening Ask. Optional advanced configuration remains under Options.
- Described exact search scope: local memory index only; family circle contents are excluded.
- Suggested prompts use actual stored people names, places, favorites and latest record. No invented children, milestones or content examples.
- No-key local search uses explicit fields and returns up to six matching records. It can list recent/favorite memories and filter by stored date, names, places and text. It does not understand unseen video/audio.
- Consumer loading and error messages suppress raw technical exceptions. Draft remains until success and survives failure/configuration recreation. Failed answers are not appended to model conversation history.
- API setup describes sending selected frames and stored metadata/conversation to the configured external provider and possible account charges.
- Invalid obvious key format does not overwrite the existing saved key. Empty value disables the provider.
- `MemoryFacts.answer(memory, question): String?` deterministically handles explicit date/day/time/place/participants/stored-description questions. Unsupported narratives return null. Unknown fields are stated as unknown. Face-match names are qualified as potentially wrong; manually added names have separate provenance; hidden names are excluded.
- `timeSource=imported` is explicitly described as the added timestamp, never the unknown original capture timestamp.

Preserved:
- Existing Nav/Activity/XML IDs, memory storage, ClaudeClient contracts, capture, buffer, audio, face recognition, family and sharing logic.
- Existing optional background analysis when the user has configured a provider. No live backend writes or messages were executed during implementation.

Verification:
- Ten JVM unit tests added for known/missing/imported timestamps, missing location despite coordinates, known place, unknown participants, hidden/manual participant provenance, stored descriptions, and unsupported questions.
- Standalone Kotlin 1.9.22 compilation succeeded against the actual extracted `Memory` data class, `HighlightPolicy`, SDK `android.jar`, and JUnit. The only support stub was `Labels.he`, which the tests do not exercise. JUnitCore execution returned `OK (10 tests)`, exit 0. This proves the metadata class contract and deterministic business behavior; it does not verify an Activity, an APK or a device.
- Shared build agent owns final Android compilation and integrated test execution. No successful Android build or UI runtime test is claimed by this note.

Limitations:
- Ask conversations remain ephemeral except the drafted query. This is an assistant search, not a family messaging channel.
- Local search is simple metadata search, not semantic retrieval and not a complete video/audio question-answering engine.
- With a configured cloud provider, reliability still depends on that provider and the underlying ClaudeClient.
- The existing design assumes the device's current timezone for displayed timestamps.
