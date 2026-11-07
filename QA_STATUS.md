# Quality status: v6.0.17

**PRE-RELEASE — functional acceptance is not complete.**

Verified by local static inspection on 2026-11-07:
- Universal and ARM64 APK files exist and their ZIP integrity checks pass.
- Both contain an Android APK v2 signing block (signature integrity and signer identity have not been independently verified).
- Universal APK contains native libraries for arm64-v8a, armeabi-v7a, x86, x86_64.
- Source Gradle files declare 6.0.17 / versionCode 6017.

Evidence from the earlier November 5 release gate, **not an end-to-end
certification of these exact 6.0.17 binaries**:
- 79 source tests passed in the earlier release-gate log.
- 9/10 media download cases verified; long YouTube audio remained unverified
  because a stale queued download prevented a fresh test.
- A 6.0.16 emulator launch smoke check passed, but this is not a 6.0.17 check.

No Android device/emulator was connected for fresh 6.0.17 install/runtime
acceptance at publishing time. Updating from prior installations, Android 7
compatibility, media delete behavior, downloads, and player functionality
must be verified before declaring production readiness.
