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

    @Test fun skipsSystemApps() {
        assertTrue(BackgroundCleaner.shouldSkip("com.example.systemapp", ApplicationInfo.FLAG_SYSTEM, ownPackage))
        // A system flag combined with other flags still counts as a system app.
        assertTrue(
            BackgroundCleaner.shouldSkip(
                "com.example.systemapp",
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_INSTALLED,
                ownPackage,
            ),
        )
    }

    @Test fun skipsEveryWhitelistedPackage() {
        for (packageName in listOf(
            "com.android.systemui",
            "com.android.launcher3",
            "of.mediaserver.hz",
            "com.ofilm.clusterinterconnection",
            "com.autonavi.minimap",
        )) {
            assertTrue(packageName, BackgroundCleaner.shouldSkip(packageName, 0, ownPackage))
        }
    }

    @Test fun skipsEveryHozonautoPackage() {
        assertTrue(BackgroundCleaner.shouldSkip("com.hozonauto.appstore", 0, ownPackage))
        assertTrue(BackgroundCleaner.shouldSkip("com.hozonauto.settings", 0, ownPackage))
    }

    @Test fun doesNotSkipOrdinaryThirdPartyApps() {
        assertFalse(BackgroundCleaner.shouldSkip("com.example.game", 0, ownPackage))
    }

    @Test fun doesNotSkipPackagesThatOnlyLookSimilarToProtectedOnes() {
        // The car-maker rule is a prefix with a trailing dot, not a plain substring match.
        assertFalse(BackgroundCleaner.shouldSkip("com.hozonautox.game", 0, ownPackage))
        assertFalse(BackgroundCleaner.shouldSkip("com.autonavi.minimapx", 0, ownPackage))
    }
}
