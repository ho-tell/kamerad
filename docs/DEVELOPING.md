# Developing Kamerad

## Build

`karoo-ext` is bundled in `libs/maven` (see [../libs/README.md](../libs/README.md)), so no GitHub credentials are needed.
You need JDK 17 and the Android SDK (platform 34).

```
./gradlew assembleDebug     # build
./gradlew installDebug      # build and install on the connected Karoo
```

If several devices are connected, pick the Karoo with `ANDROID_SERIAL=<serial> ./gradlew installDebug`.

## Code layout

- `app/src/main/kotlin/at/tellioglu/kamerad/gopro/`: Bluetooth connection to the GoPro (Open GoPro protocol)
- `app/src/main/kotlin/at/tellioglu/kamerad/extension/`: the Karoo extension, i.e. the three tiles and the button action
- `app/src/main/kotlin/at/tellioglu/kamerad/screens/`: the small app screen (pairing, status, About)

## Publishing a release

The Karoo finds updates through the manifest that `AndroidManifest.xml` points to
(`io.hammerhead.karooext.MANIFEST_URL`). The manifest is `distribution/manifest.json`.

1. Raise `versionCode` / `versionName` in `app/build.gradle.kts` and `latestVersionCode` / `latestVersion` / `releaseNotes` in `distribution/manifest.json`.
2. Build with `./gradlew clean assembleRelease`. Always use `clean`: Kotlin can keep an old compiled copy of the version text.
   The release is signed with a key configured outside the repository, in `~/.gradle/gradle.properties`:
   `kamerad.storeFile`, `kamerad.storePassword`, `kamerad.keyAlias`, `kamerad.keyPassword`.
   Debug builds use the same key when it is set, so debug builds and releases can replace each other on the Karoo.
3. Upload the APK first, then `distribution/manifest.json`, to the web server that the manifest URL points to.

## Logo

`logo/` contains the Icon Composer source (`Kamerad.icon`), PNG exports and an SVG version.
Re-export a PNG after editing:

```
"/Applications/Icon Composer.app/Contents/Executables/ictool" logo/Kamerad.icon --export-image --output-file logo/kamerad-logo-1024.png --platform iOS --rendition Default --width 1024 --height 1024 --scale 1
```
