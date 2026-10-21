import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.OutputDirectory

plugins { alias(libs.plugins.android.library) }

// Expose real generator outputs so AGP can wire their producer task without legacy library source-set casts.
abstract class BuildOpenCvTask : Exec() {
    @get:OutputDirectory
    abstract val javaSourcesDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val resourcesDirectory: DirectoryProperty
}

val openCvNdkVersion = "28.2.13676358"
val pythonExecutable = providers.gradleProperty("pythonExecutable").getOrElse(
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
)

android {
    namespace = "com.yourapp.wine.opencv"
    compileSdk = 36
    ndkVersion = openCvNdkVersion
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Phone builds consume the checked-in, hash-verified CI output. Source builds are explicit.
val fromSource = providers.gradleProperty("opencvFromSource").getOrElse("false").toBooleanStrict()
if (!fromSource) {
    androidComponents.onVariants { variant ->
        variant.sources.java?.addStaticSourceDirectory("prebuilt/java")
        variant.sources.jniLibs?.addStaticSourceDirectory("prebuilt/jniLibs")
        variant.sources.resources?.addStaticSourceDirectory("prebuilt/resources")
    }
    val verifyOpenCvPrebuilt by tasks.registering(Exec::class) {
        commandLine(pythonExecutable, rootProject.file("tools/opencv-prebuilt.py"))
    }
    tasks.named("preBuild") { dependsOn(verifyOpenCvPrebuilt) }
} else {
// This custom Exec native build must prepare SDK prerequisites before resolving the NDK path.
// Resolve the SDK only: asking sdkComponents.ndkDirectory first throws before setup can run.
val prepareOpenCvToolchain by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Verify/install pinned SDK NDK and CMake; SDK licenses must already be accepted."
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val arguments = mutableListOf<Any>(pythonExecutable, rootProject.file("tools/build-opencv-minimal.py"),
            "--sdk", sdk, "--prepare-only")
        if (providers.gradleProperty("opencvInstallMissingSdk").getOrElse("true").toBooleanStrict()) {
            arguments += "--install-missing"
        }
        environment("JAVA_HOME", System.getProperty("java.home"))
        environment("PYTHONUTF8", "1")
        commandLine(arguments)
    }
}

// Build the pinned, hash-checked upstream core/imgproc source; no pretend JNI binaries are committed.
val buildOpenCv by tasks.registering(BuildOpenCvTask::class) {
    dependsOn(prepareOpenCvToolchain)
    inputs.files(rootProject.file("tools/build-opencv-minimal.py"), rootProject.file("tools/android_native_toolchain.py"))
    inputs.property("opencvVersion", "4.12.0")
    inputs.property("ndkVersion", openCvNdkVersion)
    javaSourcesDirectory.set(layout.buildDirectory.dir("generated/opencv/java"))
    jniLibsDirectory.set(layout.buildDirectory.dir("generated/opencv/jniLibs"))
    resourcesDirectory.set(layout.buildDirectory.dir("generated/opencv/resources"))
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        environment("JAVA_HOME", System.getProperty("java.home"))
        environment("PYTHONUTF8", "1")
        commandLine(pythonExecutable, rootProject.file("tools/build-opencv-minimal.py"), "--sdk", sdk,
            "--ndk", sdk.resolve("ndk/$openCvNdkVersion"),
            "--build", layout.buildDirectory.get().asFile)
    }
}

// Use AGP's public variant API for all three generated inputs, including the upstream license resources.
// addGeneratedSourceDirectory also makes each consumer depend on the actual build task.
androidComponents.onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(buildOpenCv) { it.javaSourcesDirectory }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildOpenCv) { it.jniLibsDirectory }
    variant.sources.resources?.addGeneratedSourceDirectory(buildOpenCv) { it.resourcesDirectory }
}
tasks.named("preBuild") { dependsOn(buildOpenCv) }

}
