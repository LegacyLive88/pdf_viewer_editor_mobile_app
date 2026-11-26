# App-specific R8 rules. Engine rules live in pdfcore/consumer-rules.pro.

# The app's own code is small; it is kept as written rather than shrunk and renamed.
#  - Crash reports are readable without a mapping file.
#  - The on-device test suite can run unchanged against the release build
#    (./gradlew :app:connectedReleaseAndroidTest -PtestRelease), so what is tested is what ships.
# The libraries (AndroidX, Material, Kotlin, coroutines) are still shrunk by R8.
-keep class io.github.legacylive88.simplepdfs.** { *; }

# Keep line numbers so stack traces point at real lines.
-keepattributes SourceFile,LineNumberTable
