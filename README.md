# Novaris Grundstruktur – Android (development)

Source: `Novaris_Grundstruktur_v1_3_Standalone.html` (Google Drive, ~113.5 MB). It has four tabs (people, districts, scenes, world) and 24 inline PNG images. The HTML, CSS and JavaScript are self-contained, with no external network dependencies.

## Current development status

- Native Android WebView shell and offline file loader: implemented.
- HTTPS manifest lookup, SHA-256 and byte-count verification, staged HTML replacement, backup and rollback: initial implementation.
- Development APK CI: configured. The debug APK is signed with the Android debug key; **not a stable production signing key**.
- **Source content and 24 images have NOT yet been bundled. The development APK displays a diagnostic placeholder instead of the actual compendium. Do not release it as a complete app.**
- Data manifest is pending and deliberately contains no downloadable update.

## Required before release

1. Transfer the exact 113 MB standalone HTML into a controlled source/artifact channel, or split its 24 embedded images into packaged assets without changing the original visual result. GitHub's single-file limit is 100 MB; this source exceeds it.
2. Bundle and verify all 24 images and original HTML interactions in an offline APK.
3. Add a stable protected signing key through GitHub Actions secrets (do not commit private keys).
4. Verify update recovery and rollback on an Android device, including power loss/interrupted activation, and prevent loading remote active HTML without suitable trust guarantees.
5. Validate exact source version and structural expectations before any update is marked published.

This branch is an implementation foundation, not a completed migration.
