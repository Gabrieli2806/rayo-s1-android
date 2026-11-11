# Lightweight TV launcher

The owner's latest target is a simplified Google TV style without Google
services. This supersedes the earlier original-Google-Home-only requirement.
The local Rayo launcher supplies dark navigation, app icons, a horizontal app
rail, an app grid, Android TV Settings access, clock and battery broadcast
display. Battery accuracy still depends on the unvalidated Android 12 health HAL.
It has no Internet permission, Google SDK, account service or recommendation feed.
The first Android 12 build is approximately 74 KB, not a RAM benchmark.

The source remains compatible with the earlier Android runtime, but development
builds use a separate signing key. Do not install them over the existing v14
launcher or change normal boot to test the UI.

```powershell
. ./local/setup/enter-project-env.ps1
./tools/build-lightweight-tv.ps1
```

The wrapper reuses or generates `local/secrets/launcher-android12-development.p12`
and its separate password file. Keep both private and copy them securely when
moving PCs; neither belongs on GitHub. A missing original launcher key is not
replaced or silently regenerated. The new wrapper delegates compilation and
signature verification to `build-launcher.ps1`, then writes a checksum record
beside `outputs/Rayo-Dark-TV.apk`.

The offline framework packager accepts `--local-launcher`, checks this record
against the built APK and stages it as an ordinary system application, without
privileged permissions. It also copies the verified panel/remote keymaps. A
temporary trial must still preserve normal v14 boot and restore factory recovery.

The original Google TV APK was tested separately: it drew its loading skeleton,
but its account provider requires privileged cross-user permission. Installing
Google Play Services into the 256 MiB trial data image failed for lack of space.
Those experiments are not the selected lightweight solution. Home and GSF were
removed from isolated Android 12 data; GMS never installed successfully.
