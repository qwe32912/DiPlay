package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundCleanerTest {
    private val ownPackage = "com.example.diplay"

    @Test fun skipsThisAppItself() {
        assertTrue(BackgroundCleaner.shouldSkip(ownPackage, 0, ownPackage))
    }

    @Test fun skipsEveryProtectedPackage() {
        for (packageName in listOf(
            "android",
            "com.android.systemui",
            "com.android.launcher3",
            "com.ofilm.clusterinterconnection",
            "of.mediaserver.hz",
            "com.android.bluetooth",
            "com.android.phone",
            "com.android.networkstack",
        )) {
            assertTrue(packageName, BackgroundCleaner.shouldSkip(packageName, 0, ownPackage))
            // Being a system app changes nothing: the protection comes from the list.
            assertTrue(
                packageName,
                BackgroundCleaner.shouldSkip(packageName, ApplicationInfo.FLAG_SYSTEM, ownPackage),
            )
        }
    }

    @Test fun doesNotSkipSystemAppsOutsideTheProtectedList() {
        // The stock apps on this head unit are all system apps and hold the memory,
        // so they are exactly what the cleanup exists for.
        assertFalse(BackgroundCleaner.shouldSkip("com.autonavi.minimap", ApplicationInfo.FLAG_SYSTEM, ownPackage))
        assertFalse(BackgroundCleaner.shouldSkip("com.hozonauto.localradiomusic", ApplicationInfo.FLAG_SYSTEM, ownPackage))
        assertFalse(BackgroundCleaner.shouldSkip("com.hozonauto.appstore", ApplicationInfo.FLAG_SYSTEM, ownPackage))
        assertFalse(BackgroundCleaner.shouldSkip("com.android.settings", ApplicationInfo.FLAG_SYSTEM, ownPackage))
    }

    @Test fun doesNotSkipOrdinaryThirdPartyApps() {
        assertFalse(BackgroundCleaner.shouldSkip("com.example.game", 0, ownPackage))
    }

    @Test fun doesNotSkipPackagesThatOnlyLookSimilarToProtectedOnes() {
        // Protection is an exact package-name match, not a prefix or substring match.
        assertFalse(BackgroundCleaner.shouldSkip("com.android.systemuix", 0, ownPackage))
        assertFalse(BackgroundCleaner.shouldSkip("com.ofilm.clusterinterconnectionx", 0, ownPackage))
        assertFalse(BackgroundCleaner.shouldSkip("androidx.core", 0, ownPackage))
    }
}
