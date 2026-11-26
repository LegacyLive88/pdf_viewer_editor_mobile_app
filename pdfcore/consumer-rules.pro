# pdfcore consumer rules (applied to any app that depends on this module).
#
# PdfBox-Android loads resources by name and instantiates a few classes reflectively.
# The engine is small relative to the app, so it is kept whole: predictable behaviour
# matters more here than a few hundred kB.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# Optional codecs PdfBox-Android references but which are not shipped (JPEG2000, JBIG2).
# They are only needed to DECODE those image types, which this app never does: page
# rendering is done by the platform PdfRenderer, not PdfBox.
-dontwarn com.gemalto.jp2.**
-dontwarn org.apache.pdfbox.jbig2.**

# BouncyCastle is only reached for certificate-encrypted PDFs.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
