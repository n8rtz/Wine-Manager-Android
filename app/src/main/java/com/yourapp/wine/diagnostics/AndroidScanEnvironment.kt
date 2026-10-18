package com.yourapp.wine.diagnostics

import android.os.Build
import com.n8rtz.winetracker.BuildConfig

object AndroidScanEnvironment {
    /** No coordinates: non-unique device/build metadata only; never queries serial, Android ID, location or accounts. */
    fun current() = ScanEnvironment(
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        buildBranch = BuildConfig.BUILD_BRANCH,
        buildCommit = BuildConfig.BUILD_COMMIT,
        workingTree = BuildConfig.BUILD_WORKING_TREE,
        packagedAbis = BuildConfig.NATIVE_ABIS,
        androidSdk = Build.VERSION.SDK_INT,
        androidRelease = Build.VERSION.RELEASE,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        hardware = Build.HARDWARE,
        deviceAbis = Build.SUPPORTED_ABIS.toList(),
    )
}
