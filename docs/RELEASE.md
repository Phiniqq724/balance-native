# Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew testDebugUnitTest assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
3. Tag `vX.Y` on `main` and attach the APK to the GitHub release.

## Signing

Release builds are signed with the key named in `local.properties` (gitignored):

```
balance.release.storeFile=/Users/<you>/.android/balance-native-release.jks
balance.release.storePassword=…
balance.release.keyAlias=balance
balance.release.keyPassword=…
```

Without those lines (CI) the release build falls back to the debug key, so it still builds, but that APK
can't update an installed release. **Back the keystore up**: every future update must be signed with it.

Release certificate:

- SHA-1 `A9:93:64:57:91:01:A3:58:4B:D2:75:CC:C7:4D:3B:15:04:FA:AD:CF`
- SHA-256 `19:0D:4D:22:FD:71:07:E7:5F:10:77:9A:2A:AF:4E:47:A0:80:BF:90:00:31:62:F7:6C:92:30:1E:B1:0E:9E:27`

Google sign-in in a release build needs an **Android OAuth client for `id.sndyy.balancenative` with that
SHA-1** in the same Google Cloud project as the Web client (see HANDOFF.md).

## Art

- Launcher icon (adaptive, themed): `python3 scripts/icon.py app/src/main/res <proof.png>`
- Widget picker previews for Month Pulse and To check: `python3 scripts/widget_previews.py`
