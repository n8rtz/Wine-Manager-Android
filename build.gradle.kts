// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// One-tap branch switch for AndroidIDE's task list: force-switches to the active Arena
// session branch and resets it to the remote state (discards local changes on it).
// Run it alone (not chained with a build): it changes the files on disk, so
// sync/build after it completes.
tasks.register<Exec>("switchToArenaBranch") {
    group = "wine"
    description = "Force-switch to the arena/a89d1ab7 session branch (discards local changes on it)."
    workingDir = rootDir
    commandLine("sh", "tools/switch-to-arena-branch.sh")
}