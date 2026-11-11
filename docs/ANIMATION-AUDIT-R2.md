# Animation audit and R2 changes

The reported SystemUI RenderThread and main-thread samples rise during playback with word highlighting and application icons. A RenderThread TID is a thread inside SystemUI, not another persistent process identifier. These screenshots identify correlated work; they do not provide a measured breakdown of drawing, driver, compositor or layout time.

## Checked paths

| Path | Finding | R2 action |
| --- | --- | --- |
| Word highlighting and gradient | The alpha mask changed with highlight width, allocating a new LinearGradient, stop arrays and ComposeShader nearly every frame. | Keep a unit alpha gradient and composition; update a reusable local matrix. Test the alpha curve against the original three-stop gradient. |
| Frame scheduling | A Choreographer animation callback requested another animation-phase invalidation callback. | Invalidate directly before the same frame's traversal. Keep native display cadence and elapsed playback time. |
| Marquee waits | Initial and loop delays still woke at every display frame. | Schedule the next callback at the wait deadline, then resume native cadence. |
| Word effects and shadows | Wave/lift/glow use per-unit transforms and shadow drawing. The reported configuration only requires highlighting; these effects remain conditional on their existing setting. | Preserve effect formulas and user preferences. |
| Line and translation layout | Child LayoutTransition objects enabled animation of their ancestor hierarchy, reaching SystemUI's surrounding layouts. | Keep transitions within the lyric containers. |
| Visibility policy | Every global-layout callback unconditionally invalidated the lyric parent after reapplying visibility. | Rely on changed child visibility, size, scale and translation to invalidate. |
| Outgoing/incoming transitions | Cancel/end callbacks could run old content changes or clear a replacement transition's handle. Zero-duration completion could overwrite the incoming handle. | Use distinct ownership tokens per phase; invalidate ownership before cancellation. Cancel transitions on lyric reset/detachment. |
| Application/provider icons | Icon strategies are static; they refresh on identity/content changes. | Preserve the existing player-switch fixes. |
| Cover rotation | Window visibility was missing from the start condition. | Require an attached, shown, visible window. |
| Cover progress ring | Detached views could retain a song-long ValueAnimator, and switching icon strategies retained its progress animator. | Cancel on detachment/strategy change; do not start while hidden. |
| Control panel marquee | Hidden windows could keep its independent frame loop; waits also woke continuously. | Stop hidden/detached loops and sleep through waits. Keep original speed and loop timing. |
| Control panel spring and popup | Existing disposal cancels spring/property animation and polling, with finite entry/exit durations. | Retain the cleanup path. |

Android View's horizontal fading edges use offscreen layers for transparent backgrounds. This remains a rendering cost when lyrics overflow. A simple shader clip is not an equivalent replacement for two overlapping background/highlight text passes: applying coverage separately changes antialiased edge opacity. R2 retains the existing compositing to preserve appearance. Replacing this path requires device graphics comparisons and profiling.

Mask matrix changes still invalidate Android's native shader instances. The cache improvement removes repeated Java shader/stop-array allocations; it does not remove all native shader work. The existing 0.1-pixel update threshold is retained.

## Validation and limits

Scheduler tests exercise wait deadlines, native cadence, hidden/finished states and reentrant restarts. Gradient tests compare alpha values throughout the highlight range and check width changes and degenerate endpoints. Transition tests exercise synchronous cancellation, stale incoming completion and immediate outgoing completion using the actual transition coordinator with a mocked animation engine.

R2 JVM regression suites: 125 tests pass across common, central, provider, subscriber, statusbarlyric and view. The Xposed Kotlin compilation passes.

The APK adds Trace sections Lyricon.wordDraw and Lyricon.scrollDraw. The paired SuperLyric tools/lyrics-power-trace.pbtxt captures SystemUI app/view/gfx scheduling for 30 seconds. Compare the same song, effects, refresh rate and foreground screen, recording playing and paused intervals separately. Keep screen brightness, thermal state and player workload comparable.

No phone is connected to this workspace. JVM tests and compilation do not establish a CPU or energy reduction, pixel equivalence, or successful native hot reload. There is no measured percentage improvement. The native display frame rate, highlighting, icons, camera/island margins and effect preferences remain enabled as before.
