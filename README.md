# Akhu

App display name: **Akhu** (repo, package ID and internal names still use WallpaperCreator).

Native Android app (Java 11, XML layouts, Gradle Kotlin DSL), built from
`Isha-Bot/WallpaperCreator — Complete Spec for Re-creation` in the vault.

- Package `com.MrBRos.wallpapercreator`, minSdk 29, target/compile SDK 35
- AGP 9.4.1, Gradle 9.8.1 (wrapper), Firebase BOM 34.19.0, Glide 5.0.7

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Firebase (optional)

Cloud features turn on only when `app/google-services.json` exists (the plugin is applied
conditionally). Without it, everything else works and the cloud buttons say cloud is disabled.

1. Create a Firebase project and register the Android app `com.MrBRos.wallpapercreator`.
2. Enable **Email/Password** sign-in, create Firestore and Storage.
3. Put `google-services.json` in `app/`.
4. Deploy rules and indexes: `firebase deploy --only firestore,storage`
   (`firebase.json` points at `firebase/`).

## Layout

| Class | Role |
|-------|------|
| `MainActivity` | Picker, preview, save/share/set, live wallpaper, saved strip, upload dialog, cloud entry |
| `LiveWallpaperSettingsActivity` | Ken Burns sliders, 5 collapsible effect layers, particle count, reload |
| `LiveWallpaperService` / `LiveWallpaperEngine` | Wallpaper service + engine, reload receiver, render thread |
| `GLWallpaperRenderer`, `EglHelper`, `ShaderProgram` | GLES3 pipeline (EGL10 instance pattern) |
| `ParticleSystem`, `EffectConfig` | 64 packed particles; prefs for all effect settings |
| `AuthActivity`, `CloudGalleryActivity`, `WallpaperDetailActivity`, `FirebaseHelper` | Cloud features |
| `WallpaperWidgetProvider`, `WidgetSetWallpaperReceiver`, `ShortcutHelper` | Quick-set widget and shortcut |

Shaders live in `app/src/main/res/raw/`.

## Behaviour notes

- **Ken Burns "Cycle"** steps through the saved wallpapers strip every `20 s / speed`.
- `LiveWallpaperSettingsActivity` is exported so the system picker's *Settings* button can open it.
- Saving keeps a private copy in `files/saved/` (used by the strip, widget, shortcut and cycle);
  deleting from the strip removes that copy, not the gallery image.
- Not device-tested yet: install on a real phone to verify GL output and wallpaper flows.
