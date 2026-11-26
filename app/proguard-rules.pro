# App-specific R8 rules. Engine rules live in pdfcore/consumer-rules.pro.
#
# NOT IN USE AT THE MOMENT: code shrinking is switched off for the release build, see
# app/build.gradle.kts and docs/BUILD_AND_RELEASE.md -> "Size and minification".
# These are the rules the app itself needs when it is switched on. They are not enough for
# the on-device tests, which also use library classes that R8 would remove.

# The app's own code is small; it is kept as written rather than shrunk and renamed, so crash
# reports are readable without a mapping file.
-keep class io.github.legacylive88.simplepdfs.** { *; }

# Keep line numbers so stack traces point at real lines.
-keepattributes SourceFile,LineNumberTable
