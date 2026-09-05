// Add to patches/src/main/kotlin/app/template/patches/shared/Constants.kt
// inside `object Constants { ... }`, next to AMAZON_*_COMPATIBILITY.
// Package name verified on Play: https://play.google.com/store/apps/details?id=com.flipkart.android
// Set version/versionCode to the APK you reverse-engineered (fingerprints are version-sensitive).

val FLIPKART_COMPATIBILITY = Compatibility(
    name = "Flipkart",
    packageName = "com.flipkart.android",
    appIconColor = 0x2874F0,
    apkFileType = ApkFileType.XAPK,
    targets = listOf(AppTarget(version = "7.8.0", versionCode = 0)),
)
