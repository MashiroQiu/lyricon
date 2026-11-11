# Paired SuperLyric 4.1 / Lyricon power build

Use this build with the integrated SuperLyric 4.1 API102/Lyricon branch. Provider protocol, island/camera edge offsets, clock/notification visibility defaults, icon switching, translation/phonetic choice and rendering effects are retained. FrameAnimator uses native vsync by default; the experimental 30 FPS cap is not enabled.

The new code stops idle/hidden animation callbacks, preserves marquee delays, avoids stale-generation redraws, separates immutable background/highlight shader caches, cancels a queued ticker start atomically with pause, and only polls subscriber position while playing, interactive, mapped and observed. Cold initialization while the screen is already off is handled by PowerManager.

Validation: 117 relevant JVM tests, Xposed compilation, signed debug APK and signed release test APK pass. The release test disables minification/resource shrinking with gradle/hotreload-test.gradle; production defaults remain unchanged. Android graphics behavior, measured energy savings and native framework hot reload remain device checks.

The signing private key from the old workspace was unavailable. The new pair uses a new matching test certificate. Export the old Lyricon configuration from Settings before uninstalling; AI API keys are intentionally omitted by the backup implementation and need re-entry. Keep old APKs for rollback. Install/enable both new modules and reboot once, then import the configuration. Future same-signed APKs are the no-reboot hot-reload path.

The corresponding SuperLyric repository contains tools/backup-module-preferences.sh (root-only backup, no automatic restore or uninstall) and tools/lyrics-power-trace.pbtxt for optional phone diagnostics. Its POWER-AND-UPGRADE-20261111.md explains the limits of per-APK battery statistics for injected modules and the paired validation procedure.
