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

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

ksp {
    // Exports each schema version to app/schemas/ so androidTest can migration-test
    // real SQL against a captured prior schema instead of trusting the code alone.
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // Test Store requires Android SDK 9.9.0 or later.
    implementation("com.revenuecat.purchases:purchases:9.9.0")

    // -- Testing --
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
