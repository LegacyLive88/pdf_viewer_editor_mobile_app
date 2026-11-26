# Building and releasing

## Requirements

- JDK 21
- Android SDK with platform 36 and build-tools 36 (Android Studio installs these; so does the
  first Gradle run when licences are accepted)
- Nothing else: Gradle 8.14.3 comes with the wrapper, and all libraries are downloaded from
  Google's Maven repository and Maven Central.

| Setting | Value |
|---|---|
| Application ID | `io.github.legacylive88.simplepdfs` (debug builds add `.debug`) |
| minSdk / targetSdk / compileSdk | 24 (Android 7.0) / 36 / 36 |
| Android Gradle Plugin / Kotlin | 8.13.2 / 2.2.21 |
| Version | `versionCode` and `versionName` in `app/build.gradle.kts` |

> The application ID is permanent once the app is published anywhere: Android treats a different
> ID as a different app. Change it now if you want another one.

## Build

Debug build (installs next to a release build, signed with the local debug key):

```bash
./gradlew :app:assembleDebug
```

Release build (minified with R8):

```bash
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. It is signed if a signing key is
configured (next section) and unsigned otherwise.

## Signing

Android only installs an update over an existing app if both are signed with the **same key**.
The release key is therefore the app's identity: keep the keystore file and its password
somewhere safe and backed up. If it is lost, existing users can only get a new version by
uninstalling first.

The key is never committed (`*.jks` and `keystore.properties` are in `.gitignore`).

**On your own machine** – put the keystore in the repository root and create
`keystore.properties` beside it:

```properties
storeFile=simplepdfs-release.jks
storePassword=…
keyAlias=simplepdfs
keyPassword=…
```

**In GitHub Actions** – add four repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SIMPLEPDFS_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `SIMPLEPDFS_KEYSTORE_PASSWORD` | keystore password |
| `SIMPLEPDFS_KEY_ALIAS` | `simplepdfs` |
| `SIMPLEPDFS_KEY_PASSWORD` | key password |

To produce the base64 text:

```bash
base64 -w0 simplepdfs-release.jks
```

Environment variables of the same names (with `SIMPLEPDFS_KEYSTORE_FILE` pointing at the
keystore) take precedence over `keystore.properties`.

To create a new key instead of using the supplied one:

```bash
keytool -genkeypair -keystore simplepdfs-release.jks -alias simplepdfs -keyalg RSA -keysize 2048 -validity 10950
```

## Continuous integration

`.github/workflows/android.yml` is written to be sparing with Actions minutes:

| Job | Runs | Does |
|---|---|---|
| `test` | push to `main`, tags `v*`, manual | JVM tests, lint, cross-validation of written PDFs |
| `build` | after `test` | release APK as a build artifact; attached to a GitHub release for tags |
| `device-test` | tags `v*`, or manual with the box ticked | the on-device suite in an emulator |

Pull requests are **not** built automatically; the workflow file says how to switch that on.

## Releasing a version

1. Raise `versionCode` (must increase with every release) and `versionName` in
   `app/build.gradle.kts`.
2. Run the tests (see [TESTING.md](TESTING.md)).
3. Tag and push:

   ```bash
   git tag v1.0.1
   ```

   ```bash
   git push origin v1.0.1
   ```

   CI builds the signed APK and attaches it to a GitHub release. Without CI, build locally
   with `./gradlew :app:assembleRelease`.

## Installing the APK on a phone

Copy the APK to the phone (or download it there), tap it, and allow "install unknown apps" for
the app you opened it from when Android asks. To put it on a connected phone from a computer:

```bash
adb install -r app-release.apk
```

## Size and minification

The release build runs R8. The app's own code and the AndroidX/Material libraries are shrunk;
PdfBox-Android is kept whole (`pdfcore/consumer-rules.pro`) because it loads resources by name
and uses reflection, and predictable behaviour matters more here than size. Most of the
remaining size is the library's character maps and the bundled fonts.

## Third-party components

| Component | Licence | Used for |
|---|---|---|
| PdfBox-Android 2.0.27.0 (+ Bouncy Castle) | Apache 2.0 (MIT-style for Bouncy Castle) | reading and writing PDF structure |
| Liberation Fonts 2.1.5 | SIL Open Font License 1.1 | text written into PDFs |
| DejaVu Sans 2.37 | Bitstream Vera / public domain | fallback for symbols |
| AndroidX, Material Components, Kotlin, kotlinx.coroutines | Apache 2.0 | app framework |

Font licence texts ship inside the app at `assets/fonts/LICENSE.txt`.
