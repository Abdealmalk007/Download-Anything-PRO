# Download Anything PRO — Android signing and user warnings

Android packages do not use Microsoft Defender SmartScreen. The equivalent
consumer trust checks are Android application signature verification and
Google Play Protect. Signing an APK with a valid Android key does NOT
guarantee that Play Protect will display no warnings.

For every update:
1. Keep the existing Android applicationId/package name.
2. Sign with the same release signing key (or supported Play App Signing key
   rotation procedure). Otherwise users cannot update the installed app.
3. Never commit private signing keys, passwords, PFX/JKS keystores or tokens.
4. Verify the resulting APK with apksigner verify --verbose --print-certs.
5. Test fresh installation and updating from the previous version on supported
   Android OS versions and multiple architectures.
6. Publish SHA-256 checksums alongside APK assets on GitHub Releases.
7. Do not claim 100% distribution readiness without real-device testing.

The MIT repository license is separate from Android signing.
Older v6.0.17 test APKs are preserved; they are not re-signed or replaced.
