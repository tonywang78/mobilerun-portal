package com.carrun.portal.service

internal object ContentProviderAccessPolicy {
    private const val ROOT_UID = 0
    // `adb shell content ...` calls come from the shell user on all supported versions.
    private const val SHELL_UID = 2000

    /** Local sibling apps that may read Portal ContentProvider (e.g. auth token). */
    val ALLOWED_CALLER_PACKAGES: Set<String> = setOf(
        "com.carrun.caragent",
    )

    fun isUidAllowed(callingUid: Int, appUid: Int): Boolean {
        return callingUid == appUid || callingUid == SHELL_UID || callingUid == ROOT_UID
    }

    fun isCallerAllowed(
        callingUid: Int,
        appUid: Int,
        packagesForUid: Array<String>?,
        allowedPackages: Set<String> = ALLOWED_CALLER_PACKAGES,
    ): Boolean {
        if (isUidAllowed(callingUid, appUid)) return true
        if (packagesForUid.isNullOrEmpty()) return false
        return packagesForUid.any { it in allowedPackages }
    }
}
