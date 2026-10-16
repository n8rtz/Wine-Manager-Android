plugins {
    alias(libs.plugins.android.application)
    // alias(libs.plugins.kotlin.android) // This is the cause of the duplicate extension error
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

val buildBranchName = runCatching {
    val process = ProcessBuilder("git", "branch", "--show-current")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val branch = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() == 0) branch.takeIf { it.isNotBlank() } else null
}.getOrNull()
    ?: System.getenv("BUILD_BRANCH")?.takeIf { it.isNotBlank() }
    ?: runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val commit = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0) commit.takeIf { it.isNotBlank() } else null
    }.getOrNull()?.let { "detached@$it" }
    ?: "unknown"

val escapedBuildBranch = buildBranchName
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

// Identifies exactly which build is installed (shown in Settings > App Information), so a wireless-debugging
// install can be matched to the source it came from even when there are many iterations between commits.
fun gitOutput(vararg args: String): String? = runCatching {
    val process = ProcessBuilder("git", *args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() == 0) output else null
}.getOrNull()

val buildCommit = gitOutput("rev-parse", "--short", "HEAD")?.takeIf { it.isNotBlank() } ?: "unknown"
// True when the working tree has uncommitted changes (the commit hash alone then does not identify the build).
// The tracked .idea/ folder is ignored here because Android Studio rewrites those files on its own.
val buildDirty = gitOutput("status", "--porcelain", "--", ".", ":(exclude).idea")?.isNotBlank() == true
val buildTimeMillis = System.currentTimeMillis()

android {
    namespace = "com.n8rtz.winetracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.n8rtz.winetracker"
        minSdk = 33
        targetSdk = 36
        versionCode = 55
        versionName = "0.5.5"
        buildConfigField("String", "BUILD_BRANCH", "\"$escapedBuildBranch\"")
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("boolean", "BUILD_DIRTY", buildDirty.toString())
        buildConfigField("long", "BUILD_TIME_MILLIS", "${buildTimeMillis}L")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Reuse the full-resolution OCR fixtures in instrumented tests without duplicating them.
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    
    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    
    // ML Kit
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.play.services)
    
    // Accompanist
    implementation(libs.accompanist.permissions)
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    // Room's query verifier uses host SQLite during KSP. Replace Room's older driver
    // with a recent artifact that includes the Windows x86_64 native library.
    ksp(libs.room.compiler) {
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }
    ksp(libs.sqlite.jdbc)

    // Image Loading
    implementation(libs.coil.compose)

    // Network
    implementation(libs.retrofit.main)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}