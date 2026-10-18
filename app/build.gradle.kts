plugins {
    alias(libs.plugins.android.application)
    // alias(libs.plugins.kotlin.android) // This is the cause of the duplicate extension error
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

// Provider-backed process output is tracked by Gradle's configuration cache. A Git pull must
// invalidate cached build metadata instead of leaving the installed app reporting an older SHA.
fun gitMetadata(vararg arguments: String): String? = runCatching {
    val output = providers.exec {
        workingDir(rootProject.projectDir)
        commandLine(listOf("git") + arguments)
        isIgnoreExitValue = true
    }
    if (output.result.get().exitValue == 0) output.standardOutput.asText.get().trim() else null
}.getOrNull()

val buildCommit = gitMetadata("rev-parse", "--short=12", "HEAD")?.takeIf { it.isNotBlank() } ?: "unknown"
val buildBranchName = gitMetadata("branch", "--show-current")?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("BUILD_BRANCH").orNull?.takeIf { it.isNotBlank() }
    ?: buildCommit.takeUnless { it == "unknown" }?.let { "detached@$it" }
    ?: "unknown"
val escapedBuildBranch = buildBranchName
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
val buildWorkingTree = when (gitMetadata("status", "--porcelain", "--untracked-files=normal")) {
    null -> "unknown"
    "" -> "clean"
    else -> "dirty"
}
val packagedNativeAbis = listOf("arm64-v8a")

android {
    namespace = "com.n8rtz.winetracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.n8rtz.winetracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 55
        versionName = "0.5.5"
        ndk { abiFilters += packagedNativeAbis }
        buildConfigField("String", "BUILD_BRANCH", "\"$escapedBuildBranch\"")
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_WORKING_TREE", "\"$buildWorkingTree\"")
        buildConfigField("String", "NATIVE_ABIS", "\"${packagedNativeAbis.joinToString(",")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    sourceSets.getByName("androidTest").assets.srcDir("../testdata")
    sourceSets.getByName("test").resources.srcDir("../testdata")
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    // Experimental Text Lab only; no ONNX runtime or Paddle model in release builds.
    debugImplementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
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
    
    // Source-built OpenCV core/imgproc + the genuine upstream Java JNI bridge (arm64 only).
    implementation(project(":opencv-minimal"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)

    // Bundled Latin model: inference never needs a network or a Play Services model download.
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
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Deliberately separate from debug builds: size acceptance must use a shrunk arm64 release APK.
tasks.register<Exec>("checkArm64ApkBudget") {
    dependsOn("assembleRelease")
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), rootProject.file("tools/check-apk-budget.py"),
        layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile)
}


/** Genuine, checksum-pinned model acquisition at BUILD time, never from the recognition path. */
abstract class PrepareTextLabPaddle : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val expected = "243a0f06d826761323e9045e9b113ab2c191c3aa50565585e628300b8eda0224"
        fun hash(file: java.io.File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32768)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val folder = outputDirectory.dir("text-lab-paddle-v1").get().asFile.apply { mkdirs() }
        val model = java.io.File(folder, "inference.onnx")
        if (model.isFile && model.length() == 16562373L && hash(model) == expected) return
        val temp = java.io.File(folder, "model.download")
        try {
            val connection = java.net.URI("https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/pp-ocrv5_mobile_rec.onnx")
                .toURL().openConnection().apply { connectTimeout = 30000; readTimeout = 30000 }
            connection.getInputStream().use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= 16562373L) { "Paddle asset exceeds pinned size" }
                    output.write(buffer, 0, n)
                }
            } }
            check(temp.length() == 16562373L && hash(temp) == expected) { "Paddle asset checksum mismatch" }
            java.nio.file.Files.move(temp.toPath(), model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            throw GradleException("Cannot prepare genuine PP-OCRv5 Text Lab model. Check build-machine access to GitHub release assets and retry. No placeholder model is allowed.", error)
        } finally { temp.delete() }
    }
}

val prepareTextLabPaddle = tasks.register<PrepareTextLabPaddle>("prepareTextLabPaddle") {
    outputDirectory.set(layout.buildDirectory.dir("generated/textLabPaddleAssets"))
}
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareTextLabPaddle, PrepareTextLabPaddle::outputDirectory)
    }
}
