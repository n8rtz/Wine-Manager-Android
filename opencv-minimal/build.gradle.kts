plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.yourapp.wine.opencv"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // AGP9 rejects Provider entries in SourceSet; preBuild explicitly wires the generator dependency below.
    sourceSets.getByName("main") {
        java.srcDir(layout.buildDirectory.dir("generated/opencv/java").get().asFile)
        jniLibs.srcDir(layout.buildDirectory.dir("generated/opencv/jniLibs").get().asFile)
        resources.srcDir(layout.buildDirectory.dir("generated/opencv/resources").get().asFile)
    }
}

// No precompiled or pretend JNI library is committed. Build the pinned, hash-checked upstream source.
val buildOpenCv by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("tools/build-opencv-minimal.py"))
    inputs.property("opencvVersion", "4.12.0")
    inputs.property("ndkVersion", "28.2.13676358")
    outputs.dir(layout.buildDirectory.dir("generated/opencv"))
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"),
            rootProject.file("tools/build-opencv-minimal.py"), "--sdk", sdk,
            "--ndk", androidComponents.sdkComponents.ndkDirectory.get().asFile,
            "--build", layout.buildDirectory.get().asFile)
    }
}
tasks.named("preBuild") { dependsOn(buildOpenCv) }
