# Status-bar render work / upstream check — 2026-11-11

The supplied screenshots show SystemUI RenderThread CPU about 35.7% during lyrics and 0.5% while paused; SurfaceFlinger also rises. These samples indicate a rendering correlation, not an on-device trace identifying a single root cause.

Code findings and changes:

- FrameAnimator previously continued requesting every display vsync after its renderer became idle. Continue only while the renderer needs animation; keep marquee delay scheduling, stop finished/static lines.
- Visibility previously checked attachment alone. Stop on hidden ancestors/windows and restart on visibility/attachment restoration.
- Cap lyric animation steps/redraws near 30 FPS, with elapsed wall time preserved for scrolling and highlight timing. This intentionally trades high-refresh animation smoothness for fewer rendering transactions.
- Word gaps need animation only when character effects are active. Static gaps wait for the next playback update.
- A renderer listener can restart an animation synchronously. Generation guards prevent the retiring callback from posting another frame or invalidating the replacement.

Validation: 7 JUnit scheduler regressions pass, including synthetic 120 Hz vsync input (31 draws including initialization over one second, rather than 121), elapsed time, hidden/finished/static states, marquee delays, restart races. The production FrameAnimator is compiled with Kotlin and scheduling is injected in the tests. A minimal Android type stub is used for this standalone JVM run; it does not test Android Choreographer timing, View visibility dispatch, rendering, or GPU/CPU measurements. A complete Android APK build and phone profiling have not been performed in this restored workspace.

Upstream: tomakino/lyricon master c93da0e and TTXXXY/lyricon master ecd34fc are already ancestors of our branch; no new upstream commits to merge.

Phone comparison: use the same song/lyric effects, display refresh rate and screen, wait past transitions, compare CPU over at least 30 seconds. Capture a SystemUI Perfetto trace if RenderThread remains high. Screen-off/hidden text should have no scheduled lyric animation frames; verify reappearance, marquee delays, seeks, translations and next-line transitions.

## Paired integration adjustment

The paired build preserves the original display refresh cadence: FrameAnimator's default frame interval is zero (native vsync). The 30 FPS path is retained only as an injected scheduler option exercised by tests; it is not applied to production views. Idle/hidden/finished frames still stop, and immutable background/highlight/composition shaders are reused without changing gradient/effect formulas.

The new background patch synchronously registers/cancels PositionTicker jobs so a queued start cannot run after stop. Subscriber shared-memory reads now require active playback, screen-on, a mapped buffer and listeners; release removes the screen receiver and waits for reader completion before unmapping. Initial screen state is read from PowerManager, including module initialization while the screen is already off. Three queue-order/cancellation tests cover start/stop/restart races.

Full Android validation: 117 relevant JVM tests and the Xposed module compilation pass; signed debug and unminified release variants build. Native visibility delivery, graphics equivalence, power savings and real hot reload still require phone checks. The APK uses a new test certificate; see the paired SuperLyric installation notes before replacing the old app. A new private signing keystore is kept outside Git.
