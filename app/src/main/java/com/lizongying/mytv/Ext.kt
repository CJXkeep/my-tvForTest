package com.lizongying.mytv

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

private val Context.packageInfo: PackageInfo
    get() = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, 0)
    } else {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    }

/**
 * Return the version name of the app which is defined in build.gradle.
 * eg:1.0.0
 */
val Context.appVersionName: String get() = packageInfo.versionName
