# GrapheneOS performance regression — investigation & attribution guide

## Summary

GrapheneOS users reported severe slowdown and intermittent freezes starting with
Wisp 1.0.2 (2026-04-26). Stock Android users did not report it (caveat: the Nostr
userbase skews heavily GrapheneOS, so the negative evidence is weak).

Wisp 1.0.2 contained commit `f68b40f` ("support 16 KB page size for Android 15+"),
which changed exactly two things that survived to later releases:

1. `jniLibs.useLegacyPackaging = true → false` — native libs stored uncompressed
   and mmap'd directly from the APK (`extractNativeLibs=false`)
2. `secp256k1-kmp 0.16.0 → 0.19.0` — libsecp256k1 0.7.0 upstream, rebuilt with
   NDK 28 for 16 KB ELF alignment (this JNI lib runs once per relay event for
   Schnorr verification, plus 2 ECDH calls per DM gift wrap)

Since Dark Wisp is distributed by direct APK download only (not Google Play),
16 KB page-size packaging buys nothing — GrapheneOS doesn't ship 16 KB-page
kernels — so **both halves of f68b40f were reverted** rather than spending a
release cycle attributing blame while users suffer.

## What the artifact audit established (2026-06-10)

All shipped Wisp release APKs (v1.0.0, v1.0.2, v1.0.5, v1.1.1) were audited with
`tools/audit-apk.sh`: every one is release-signed with the same cert, R8-minified,
correctly aligned, internally consistent packaging, baseline profile present.
**A malformed/debug artifact is ruled out as the cause.**

Side effect of the packaging change, measured: download size went 44 MB (v1.0.0,
compressed libs) → 82 MB (v1.0.2, stored libs). Reverting to legacy packaging is
the right call for direct-APK distribution regardless of the perf question.

## Why GrapheneOS would be hit harder

GrapheneOS runs hardened_malloc and enables MTE (memory tagging) by default for
user apps on Pixel 8/9 — both make native-heap allocation churn significantly more
expensive than stock Android. The app bundles ~13 native libs (secp256k1 JNI,
ObjectBox, Breez Rust SDK, ML Kit/TFLite, Media3, CameraX), and event ingestion
performs one JNI Schnorr verify per relay event (`RelayPool` → `Event.verifySignature`).

## Attribution matrix (run when a GrapheneOS tester is available)

Cheapest signal first — on the **existing slow build**, no rebuild needed:

1. App info → Exploit protection → disable **Memory tagging** → retest scroll +
   cold start. If this alone fixes it: cause is native-alloc churn under MTE;
   publish the per-app toggle as interim guidance and look at allocation-heavy
   native paths (Breez, ObjectBox, secp256k1 JNI).
2. Re-enable MTE, enable **Exploit protection compatibility mode** → retest.

Then build variants (all release builds, installable sequentially):

| Variant | Config | Isolates |
|---|---|---|
| V-fix | this branch (full revert) | the shipped fix |
| V-pkg | only `useLegacyPackaging = true`, secp stays 0.19.0 | packaging variable |
| V-secp | only `secp256k1-kmp = 0.16.0`, packaging stays false | secp variable |

Per-variant protocol: install → launch twice → `adb shell pm bg-dexopt-job` →
relaunch, then capture:

- `adb logcat -s ProfileVerifier` (expect `compiledWithProfile=true` after dexopt)
- `adb shell dumpsys gfxinfo <pkg> reset`, scroll feed 60 s, `dumpsys gfxinfo <pkg>`
  → janky-frame % and P90/95/99
- `adb shell am start-activity -W <pkg>/.MainActivity` cold start ×3, median
- 30 s Perfetto trace (`sched gfx view dalvik binder_driver`) during feed load;
  release builds are `<profileable android:shell="true"/>` so simpleperf works
- on any freeze: `adb bugreport` immediately (captures /data/anr traces)

Decision tree:

- V-pkg fast / V-secp slow → packaging was guilty; keep legacy packaging, secp
  0.19.0 may be re-bumped
- V-secp fast / V-pkg slow → secp256k1-kmp 0.19.0 is guilty; keep the 0.16.0 pin
  and file upstream at ACINQ/secp256k1-kmp (NDK-28 build under MTE/hardened_malloc)
- MTE-off fixes the old build → MTE interaction; fix ships anyway, document toggle
- everything still slow including V-fix → f68b40f exonerated; pivot to
  ProfileVerifier output (JIT-only execution on sideloads) and Perfetto
  `Dispatchers.Default` saturation from per-event signature verification

Record the outcome here so the innocent half of f68b40f can be knowingly
re-applied if Play distribution or 16 KB-kernel devices ever matter.

## Related fixes shipped with the revert (this branch)

- `app/src/main/baseline-prof.txt` rules updated `com/wisp/app` → `com/darkwisp/app`
  (stale since the rebrand — would have shipped a no-op baseline profile, i.e.
  zero install-time AOT and JIT-heavy cold starts on every device)
- Release signing moved into Gradle (`RELEASE_STORE_*` in local.properties or env)
  so the published APK is exactly what `assembleRelease` produces — no manual
  post-processing step to corrupt packaging
- `tools/audit-apk.sh` added as a mandatory pre-release gate
- `ProfileVerifier` status logged at startup (tag `ProfileVerifier`) and into
  DiagnosticLogger for field confirmation that baseline-profile AOT happened
