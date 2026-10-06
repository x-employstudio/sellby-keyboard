import com.android.build.api.variant.ApplicationVariant
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    id("com.google.devtools.ksp") version "2.3.12"
}

// Release signing (see README / docs/RELEASE.md): the upload key lives OUTSIDE the repo. keystore.properties
// (git-ignored) holds storeFile / storePassword / keyAlias / keyPassword. Without it `assembleRelease` /
// `bundleRelease` produce an UNSIGNED artifact, unless -PlocalReleaseSign is passed: that signs with the debug
// key so the minified release build can be installed on a phone for smoke tests (never upload that one).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sellby.keyboard"
        minSdk = 23
        targetSdk = 37
        // Sellby's own version. The code keeps counting up from HeliBoard's 4101 on purpose: AppUpgrade
        // compares against VERSION_CODE (its newest step is "<= 4005"), so it must never go down.
        versionCode = 5005
        versionName = "1.0.0"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // This is the build that ships to Google Play. It never loads a user-supplied native library
            // (JniUtils only allows that in debug builds - the old "nouserlib" build type is gone).
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            // native debug symbols go into the AAB so Play Console can symbolicate native crashes
            ndk { debugSymbolLevel = "FULL" }
            signingConfig = when {
                keystoreProps.isNotEmpty() -> signingConfigs.getByName("release")
                project.hasProperty("localReleaseSign") -> signingConfigs.getByName("debug")
                else -> null
            }
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "release") {
                // The release build ships no word-list dictionaries (assets/dicts/main_*.dict, 18 languages, none of
                // them Indonesian, ~30 MB): suggestions are off by default, and their licenses differ per language
                // (some are non-commercial) - see THIRD_PARTY_NOTICES.md. The debug build already runs fine
                // without one of them (main_ro.dict below).
                // (aapt ignore patterns only take a wildcard at the very start or end, so "main_*.dict" matches
                // nothing: list the files by name.)
                val dicts = project.file("src/main/assets/dicts").listFiles { f -> f.name.endsWith(".dict") }
                variant.androidResources.ignoreAssetsPatterns = dicts?.map { it.name }.orEmpty()
            }
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "Sellby_${defaultConfig.versionName}-${variant.buildType}.apk"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    // The app switches its resource locale at runtime (RunInLocale) and only has en + in: ship both in the base
    // module instead of letting Play split languages by device settings, otherwise the Indonesian strings would
    // be missing on an English-language phone.
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
        // The Indonesian translation is deliberately partial (the UI text of Sellby itself is in Kotlin):
        // every string it lacks falls back to the English default, which is what we want.
        disable += "MissingTranslation"
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // Pulls androidx.fragment from the old 1.1.0 (a transitive dependency of recyclerview/viewpager2) up to a
    // current one. lintVitalRelease treats "fragment < 1.3.0 next to registerForActivityResult"
    // (ContactPickerActivity, QrisPhotoPickerActivity, ...) as a FATAL error, which stops every release build.
    implementation("androidx.fragment:fragment:1.8.9")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Sellby: Room (Fase 4 Diperluas data layer - separate from the existing hand-rolled
    // Database.kt/ClipboardDao SQLiteOpenHelper, which stays untouched)
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Sellby: Google Play In-App Review (popup on trial day 3). The real dialog only appears for builds
    // installed from Google Play (Internal testing is enough); sideloaded/debug builds fail silently.
    implementation("com.google.android.play:review-ktx:2.0.2")
    // Sellby: Google Play Billing - the one-time "sellby_premium" purchase (see sellby/companion/billing/).
    // Library 8+ is required by Google Play from 2025; 9.x is the current line.
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    // Sellby: Block Store keeps a copy of the trial start outside the app's own data, so the free trial
    // stays once-per-device across reinstall / Clear data (best effort, needs Google Play services).
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01")) // newer requires minSdk 23
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}
