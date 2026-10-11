# NAS Explorer (Android)

A file manager for your NAS (SMB/CIFS shares): browse, stream videos, view photos,
download to the phone, upload from the phone, copy/move/rename/delete on the NAS.

## Get the APK
Every push to this branch is built by GitHub Actions
(`.github/workflows/build-android-apk.yml`). Open the repo's **Releases** page on your phone,
download `NAS-Explorer.apk`, tap it and allow "install unknown apps" for your browser.

## Build locally
Needs JDK 17 and the Android SDK (platform 35): `./gradlew assembleRelease`

## Notes
- Protocol: SMB 2/3 (via smbj). SMB1-only NAS devices are not supported.
- Downloads are saved to `Downloads/NAS/` on the phone.
- Passwords are stored encrypted with the Android Keystore.
- The APK is signed with the keystore in `app/nas-explorer.jks` (password in `app/build.gradle.kts`).
  That key is public; fine for a personal sideloaded app, don't reuse it for anything you publish.
