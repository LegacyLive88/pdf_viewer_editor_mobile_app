import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is read from (in order): environment variables (CI), then an untracked
// keystore.properties file in the repository root (local builds). If neither is present the
// release build is produced UNSIGNED, which keeps `assembleRelease` working for contributors
// without access to the key. See docs/BUILD_AND_RELEASE.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("SIMPLEPDFS_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("SIMPLEPDFS_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("SIMPLEPDFS_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("SIMPLEPDFS_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null } &&
    rootProject.file(releaseStoreFile!!).exists()

android {
    namespace = "io.github.legacylive88.simplepdfs"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.legacylive88.simplepdfs"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // `-PtestRelease` runs the on-device tests against the minified release build instead of
    // the debug build, i.e. against the binary that is actually shipped.
    if (project.hasProperty("testRelease")) testBuildType = "release"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking (R8) is deliberately OFF. With it on, the on-device test suite
            // cannot run against the release build (R8 removes library code the tests share
            // with the app), so the binary that ships would be one that no device test has
            // exercised. Unshrunk, the release APK is the debug code, release-signed: what is
            // tested is what ships. The price is size (10.5 MB instead of 7 MB).
            // Before switching it on, read docs/BUILD_AND_RELEASE.md -> "Size and minification".
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                it.testLogging {
                    events("failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }

    // The on-device tests use the same PDF fixtures as the engine's JVM tests.
    sourceSets["androidTest"].assets.srcDir("../pdfcore/src/test/resources")
    sourceSets["test"].resources.srcDir("../pdfcore/src/test/resources")

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":pdfcore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
