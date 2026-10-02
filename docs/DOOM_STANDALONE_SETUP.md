# DOOM Standalone Quest Build

This fork contains a Quest-focused DOOM (2016) build path using the existing `app.gamenative.doomtest` package.

## Safety rule

Do not uninstall the currently installed DOOM test build until the existing DOOM installation and saves have been copied to public Downloads and verified there. The current installed APK was signed with an ephemeral GitHub Actions debug key, so the first permanently signed release cannot update it in place.

## Public storage layout

The custom build writes user-facing diagnostics under:

```text
Download/GameNative-DOOM/
├── Logs/
│   ├── App/
│   ├── Crash/
│   ├── Wine/
│   ├── DebugRun/
│   └── Diagnostics/
├── Updates/
└── Backup/
```

DOOM diagnostics include:

- one-second GameNative memory samples
- Wine/FEX process RSS snapshots
- Android low-memory and trim-memory callbacks
- selected Wine debug channels
- Wine output path
- continuous Android logcat during the DOOM session
- current/previous session rotation so a killed session survives relaunch

## Permanent signing key

Generate one private Android signing key and keep an offline backup. Never commit the keystore to the repository.

Example with JDK `keytool`:

```powershell
keytool -genkeypair `
  -v `
  -keystore gamenative-doom.jks `
  -alias gamenative-doom `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

Convert the keystore to Base64 for GitHub Actions:

```powershell
$bytes = [System.IO.File]::ReadAllBytes("$PWD\gamenative-doom.jks")
[Convert]::ToBase64String($bytes) |
  Set-Content -NoNewline "gamenative-doom.base64.txt"
```

Create these repository Actions secrets:

- `DOOM_KEYSTORE_BASE64`
- `DOOM_KEY_ALIAS`
- `DOOM_KEY_PASSWORD`
- `DOOM_STORE_PASSWORD`

The release workflow deliberately fails if any secret is missing.

## Release workflow

Run:

`.github/workflows/doom-release.yml`

Example first permanent release:

- version name: `1.0.0`
- version code: `1000`

Every future release must use a larger version code.

The workflow:

1. Builds the Legacy XR release bundle.
2. Creates one universal Quest APK.
3. Signs it using the permanent keystore.
4. Verifies the APK package is `app.gamenative.doomtest`.
5. Prints the signing certificate.
6. Computes SHA-256.
7. Writes `update.json`.
8. Publishes `GameNative-DOOM.apk` and `update.json` to a `doom-v*` GitHub Release.

## In-app updater

Settings > Debug > Check for updates reads public `doom-v*` releases from `MrEGS-Ops/GameNative`.

Before opening Android's installer it verifies:

- remote versionCode is newer
- APK package name matches the installed package
- APK versionCode matches `update.json`
- APK SHA-256 matches `update.json`
- APK signing certificate matches the installed app

The downloaded APK is kept under:

`Download/GameNative-DOOM/Updates/`

Android/Horizon OS still requires the user to approve the final app update.

## One-time migration of the existing DOOM install

The existing fork maps Wine drive `D:` to Android public Downloads.

Before removing the old ephemeral-key build, copy at minimum:

```text
C:\Program Files (x86)\Steam\steamapps\common\DOOM
C:\Program Files (x86)\Steam\steamapps\appmanifest_379720.acf
C:\users\xuser\Saved Games\id Software\DOOM
```

to:

```text
D:\GameNative-DOOM\Backup\
```

Verify the backup from the Quest Files application before uninstalling anything.

After installing the first permanently signed build, restore the files into the new container and let Steam/GameNative verify the existing game data instead of downloading the full game again.

## Future updates

After the one-time migration, every future custom build must keep:

- package: `app.gamenative.doomtest`
- the same permanent signing key
- a higher Android versionCode

Then the in-app updater can install releases over the existing app while preserving the Wine container, DOOM installation, saves, and settings.
