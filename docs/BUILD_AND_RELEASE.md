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

The library versions in `gradle/libs.versions.toml` are the ones the app was tested with.
Lint lists newer ones ("a newer version of … is available"); those notes are expected. Updating
is routine maintenance: change the version, run the test layers in [TESTING.md](TESTING.md).
Android Gradle Plugin 9 needs Gradle 9, so the two move together.

## Build

Debug build (installs next to a release build, signed with the local debug key):

```bash
./gradlew :app:assembleDebug
```

Release build:

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
| `test` | push to `main`, tags `v*`, manual | JVM tests, lint (both modules), cross-validation of written PDFs |
| `build` | after `test` | release APK as a build artifact; attached to a GitHub release for tags |
| `device-test` | tags `v*`, or manual with the box ticked | the on-device suite in two emulators: Android 7.0 (the oldest supported) and Android 14 |

Pull requests are **not** built automatically; the workflow file says how to switch that on.

State of the workflow: `test` and `build` have run on GitHub and passed (first on the merge of
pull request #1). `device-test` has not been run on GitHub yet – the same suite was run by
hand on emulators instead (see [TESTING.md](TESTING.md)) – so expect to give it a first
manual run (Actions → Android → Run workflow, box ticked) before relying on it for a tag.

Lint is part of the `test` job and fails it on errors:

```bash
./gradlew :pdfcore:lintDebug :app:lintDebug
```

It is expected to report no errors and only the "newer version available" notes.

## Releasing a version

1. Raise `versionCode` (must increase with every release) and `versionName` in
   `app/build.gradle.kts`.
2. Run the tests (see [TESTING.md](TESTING.md)). With the signing key on your machine and a
   phone or emulator attached, the device suite can be run against the release build itself:

   ```bash
   ./gradlew -PtestRelease :app:connectedReleaseAndroidTest
   ```

3. Tag and push:

   ```bash
   git tag v1.0.1
   ```

   ```bash
   git push origin v1.0.1
   ```

   CI runs the tests (including the device suite, on an emulator), builds the APK – signed if
   the four secrets are set – and attaches it to a GitHub release. Without CI, build locally
   with `./gradlew :app:assembleRelease`.

## Installing the APK on a phone

Copy the APK to the phone (or download it there), tap it, and allow "install unknown apps" for
the app you opened it from when Android asks. To put it on a connected phone from a computer:

```bash
adb install -r app-release.apk
```

## Size and minification

The release APK is about 10.5 MB. Roughly: 4.5 MB code (the PDF library, AndroidX, Material,
Kotlin), 2.8 MB fonts that are written into edited PDFs, 1.6 MB character maps and font
metrics of the PDF library, 1.5 MB resources.

**Code shrinking (R8) is switched off** (`isMinifyEnabled = false` in `app/build.gradle.kts`).
With it on the APK would be 7.0 MB instead of 10.5 MB, but the on-device test suite cannot run
against the release build: the tests share library code with the app (Kotlin, AndroidX), and
R8 removes or renames what the app itself does not use, so the tests crash on start with
`NoSuchMethodError`. A release that no device test can exercise was judged a bad trade for
3.5 MB. Unshrunk, the release build is the same code as the debug build, signed with the
release key, and the whole device suite runs against the very APK that ships:

```bash
./gradlew -PtestRelease :app:connectedReleaseAndroidTest
```

To switch shrinking on later: set `isMinifyEnabled` and `isShrinkResources` to `true`; the
rules the app itself needs are already in `app/proguard-rules.pro` and
`pdfcore/consumer-rules.pro`. Then either add keep rules for everything the device tests use
from the libraries, or accept that the shrunk APK is checked by hand – and check it by hand,
on a phone, before every release.

**Bouncy Castle is left out** (`pdfcore/build.gradle.kts`). The PDF library declares this
large cryptography library (2 MB in the unshrunk APK) as a dependency, but only two of its
classes use it, both for PDFs encrypted for the holder of a certificate. Such files cannot be
opened by this app in any case, because Android's renderer cannot decrypt them. Password-
encrypted files use Android's own cryptography. If certificate encryption is ever to be
supported, remove the exclusion and add Bouncy Castle's licence to the notices.

## Third-party components

| Component | Licence | Used for |
|---|---|---|
| PdfBox-Android 2.0.27.0 | Apache 2.0 | reading and writing PDF structure |
| Liberation Fonts 2.1.5 | SIL Open Font License 1.1 | text written into PDFs |
| DejaVu Sans 2.37 | Bitstream Vera / public domain | fallback for symbols |
| AndroidX, Material Components, Kotlin, kotlinx.coroutines | Apache 2.0 | app framework |

All of these allow free and commercial distribution. What they ask for is that their licence
texts travel with the app, which they do: **About → Open-source licences** shows them.

| File | Contents |
|---|---|
| `app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt` | notices and licences of the libraries |
| `pdfcore/src/main/assets/fonts/LICENSE.txt` | licences of the bundled fonts |

**When a library is added, removed or updated**, bring `THIRD_PARTY_NOTICES.txt` up to date
(the library's `LICENSE` and `NOTICE` files are inside its jar under `META-INF/`).
`OpenSourceNoticesTest` fails if a library in the version catalog has no entry.
