plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.root.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.root.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The RevenueCat key is injected via BuildConfig rather than hardcoded, so it
        // can be supplied per-developer in a user-level, untracked gradle.properties
        // (see README's "Optional Test Store purchases" section) and never committed.
        val revenueCatKey = providers.gradleProperty("ROOT_REVENUECAT_API_KEY").orElse("").get()
        buildConfigField("String", "REVENUECAT_API_KEY", "\"${revenueCatKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val contentCatalogUrl = providers.gradleProperty("ROOT_CONTENT_CATALOG_URL").orElse("").get()
        require(contentCatalogUrl.isEmpty() ||
            (contentCatalogUrl.startsWith("https://") && contentCatalogUrl.none { it.isWhitespace() || it == '"' || it == '\\' })) {
            "ROOT_CONTENT_CATALOG_URL must be an HTTPS URL without whitespace."
        }
        buildConfigField("String", "CONTENT_CATALOG_URL", "\"$contentCatalogUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].resources.srcDir(rootProject.file("content/editorial"))
}

ksp {
    // Exports each schema version to app/schemas/ so androidTest can migration-test
    // real SQL against a captured prior schema instead of trusting the code alone.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Only this public-safe development manifest is bundled, and only in debug.
// Raw corpora and editorial permission records are never Android asset sources.
val prepareDevelopmentContent by tasks.registering(Copy::class) {
    from(rootProject.file("content/editorial/shona-pilot.json"))
    into(layout.buildDirectory.dir("generated/contentAssets/debug/content"))
}
android.sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/contentAssets/debug").get().asFile)
// Every task that reads the debug asset source set (asset merging, lint's model/analysis
// of that source set, etc.) must declare this dependency explicitly, not just the merge
// task — Gradle's task validation otherwise flags an undeclared implicit dependency.
// Match lint tasks by prefix rather than an exact list: AGP defines several
// (lintAnalyzeDebug, lintReportDebug, lintFixDebug, ...) that all read the same source set.
tasks.matching {
    it.name == "mergeDebugAssets" ||
        it.name == "generateDebugLintReportModel" ||
        (it.name.startsWith("lint") && it.name.contains("Debug"))
}.configureEach {
    dependsOn(prepareDevelopmentContent)
}

dependencies {
    // -- Compose --
    // Stay on the Kotlin-2.0-compatible Compose line; take its patched UI artifacts.
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // Home-screen widget (see widget/RootWidget.kt); Glance renders with RemoteViews,
    // not Compose directly, so it cannot share the bundled custom-font pipeline.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // -- Room (local-first persistence, per DESIGN.md §7) --
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // -- Serialization (Course-Pack-style JSON, per DESIGN.md §7) --
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Test Store requires Android SDK 9.9.0 or later.
    implementation("com.revenuecat.purchases:purchases:9.9.0")

    // -- Testing --
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // Only used to give the RevenueCat-facing PaywallViewModel a real Android Context
    // (SharedPreferences, etc.) in a JVM unit test; RevenueCat calls themselves are
    // swapped out via PurchasesGateway, never actually invoked under test.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
