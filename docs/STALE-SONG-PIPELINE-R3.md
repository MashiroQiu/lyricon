# Song processing and power follow-up

The reported historical lyrics also occur inside SuperLyric; the paired SuperLyric R3 fixes a reproduced API cache rollback and media-session replay issues. This Lyricon change addresses separate processing races and avoidable work.

SongProcessingSession owns raw input, request generations, pending processing and delivered-content equality. Input replacement, source invalidation and final delivery are serialized on the main dispatcher. Normalization and optional enhancement stay on workers. The final generation check runs after processing, immediately before notifying display listeners.

Source changes and text mode invalidate pending song work and the reprocessing cache. This also blocks an old enhancement that ignores cancellation. Deduplication compares content rather than hash codes, so different songs with colliding hashes are not suppressed. Identical raw input does not restart processing; explicit configuration changes can still reprocess it.

Playback/source notifications keep main-thread ordering. New sources clear cached song, position and playing state before fresh state arrives. Position notifications keep at most one pending UI update; animation cadence is unchanged. Song logging records identity and line count instead of formatting every lyric and word timing.

Tests cover queued stale delivery, uncancellable old enhancement, source invalidation, duplicate input/output, hash collisions, explicit reprocessing and redisplaying an identical song for a new source. They do not replace device playback, graphics or battery measurements. R2 animation settings and camera/island geometry are retained.
