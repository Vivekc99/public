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
- The APK is signed with a private key kept in GitHub Actions secrets (`KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). It is not in the repo. Keep a backup of the
  keystore + password: without them an installed app can't be updated, only reinstalled.
- Protocols: SMB, SFTP (SSH), WebDAV.
