# Reel Brain (Android)
Counts Instagram Reels and YouTube Shorts using an Accessibility Service. All data stays on the phone.

## Get the APK without Android Studio
1. Create a free GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, run **Android CI** (it also runs on every upload).
3. When it finishes, open the run and download **ReelBrain-APK**, unzip it, and install `app-debug.apk`.

## First run
Install, open Reel Brain, tap **Open Accessibility settings**, choose Reel Brain and switch it On.
On Android 13+ if the switch is greyed out: App info > three dots > "Allow restricted settings", then retry.

## If counting stops after an Instagram/YouTube update
Turn on Compatibility mode in Settings, or update the view ids at the top of ReelCounterService.kt.
