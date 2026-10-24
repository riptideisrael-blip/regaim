# Social layer architecture (plan, not built)

Rule: every feature asks `Privacy.canSee(level, viewer)`. Levels: Private < Family < Friends < Public.

Exists (0.19): Privacy enum, Memory.privacy (default private), publish to Family/Public, public feed, likes, comments, report, block, admin hide/delete.
Missing: Friends level (needs follow/friend graph), Profiles (public name/avatar/bio table), Follow, Search, Shares, per-post audience column on server, server-side moderation queue, private bucket for community media.

Next server steps: profiles(user_id, handle, name, avatar, bio); follows(follower, followee, status); community_posts.audience in ('public','friends'); RLS: friends posts visible only to accepted followers.

## 0.30 additions
- Music: `Music.renderWav` renders on-device music (4 moods) as a WAV with fades; mixed under original sound when sharing, or as the soundtrack of period videos. No licensed tracks are bundled.
- Period video: `PeriodVideo` + `PeriodVideoActivity` (week/month): intro card + best moments + music + watermark, via media3 Composition.
- Album covers: `AlbumCovers` (long-press an album).
- Pets: profile members with relation dog/cat/pet; `Pets` uses ML Kit image labels and names a pet only when the profile has exactly one of that kind. `Memory.pets/petsScanned`.
