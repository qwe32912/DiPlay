package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IntelSmoothModePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() = prefs.edit().clear().apply()

    private fun setDistinctCurrentValues() {
        AirPlayPersistence.saveDisplayScalePercent(context, 90)
        AirPlayPersistence.saveFps(context, 60)
        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveSmoothVideo(context, false)
        AirPlayPersistence.saveDirectVideoOutput(context, false)
    }

    @Test fun freshInstallHasNoSnapshotAndModeIsOff() {
        assertNull(AirPlayPersistence.loadIntelSmoothSnapshot(context))
        assertFalse(AirPlayPersistence.isIntelSmoothModeActive(context))
    }

    @Test fun applySnapshotsCurrentValuesAndWritesTargets() {
        setDistinctCurrentValues()

        AirPlayPersistence.applyIntelSmoothMode(context)

        assertEquals(
            AirPlayPersistence.IntelSmoothSnapshot(
                displayScalePercent = 90,
                fps = 60,
                hevcEnabled = true,
                smoothVideo = false,
                directVideoOutput = false,
            ),
            AirPlayPersistence.loadIntelSmoothSnapshot(context),
        )
        assertEquals(67, AirPlayPersistence.loadDisplayScalePercent(context))
        // The legacy tenths key follows the percent the same way the CarPlay host derives it.
        assertEquals(7, AirPlayPersistence.loadDisplayScaleTenths(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertFalse(AirPlayPersistence.loadHevcEnabled(context))
        assertTrue(AirPlayPersistence.loadSmoothVideo(context))
        assertTrue(AirPlayPersistence.loadDirectVideoOutput(context))
        assertTrue(AirPlayPersistence.isIntelSmoothModeActive(context))
    }

    @Test fun applyingTwiceKeepsTheFirstSnapshot() {
        setDistinctCurrentValues()
        AirPlayPersistence.applyIntelSmoothMode(context)
        AirPlayPersistence.saveFps(context, 24)

        AirPlayPersistence.applyIntelSmoothMode(context)

        assertEquals(60, AirPlayPersistence.loadIntelSmoothSnapshot(context)?.fps)
        assertEquals(30, AirPlayPersistence.loadFps(context))
    }

    @Test fun restoreWritesBackSnapshotAndClearsIt() {
        setDistinctCurrentValues()
        AirPlayPersistence.applyIntelSmoothMode(context)

        assertTrue(AirPlayPersistence.restoreIntelSmoothSnapshot(context))

        assertNull(AirPlayPersistence.loadIntelSmoothSnapshot(context))
        assertEquals(90, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(9, AirPlayPersistence.loadDisplayScaleTenths(context))
        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertTrue(AirPlayPersistence.loadHevcEnabled(context))
        assertFalse(AirPlayPersistence.loadSmoothVideo(context))
        assertFalse(AirPlayPersistence.loadDirectVideoOutput(context))
        assertFalse(AirPlayPersistence.isIntelSmoothModeActive(context))
    }

    @Test fun restoreWithoutSnapshotChangesNothing() {
        setDistinctCurrentValues()

        assertFalse(AirPlayPersistence.restoreIntelSmoothSnapshot(context))

        assertEquals(90, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(60, AirPlayPersistence.loadFps(context))
        assertTrue(AirPlayPersistence.loadHevcEnabled(context))
        assertFalse(AirPlayPersistence.loadSmoothVideo(context))
        assertFalse(AirPlayPersistence.loadDirectVideoOutput(context))
    }

    @Test fun manuallyMatchingValuesReadsAsActiveWithoutSnapshot() {
        AirPlayPersistence.saveDisplayScalePercent(context, 67)
        AirPlayPersistence.saveFps(context, 30)
        AirPlayPersistence.saveHevcEnabled(context, false)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveDirectVideoOutput(context, true)

        assertTrue(AirPlayPersistence.isIntelSmoothModeActive(context))
        assertNull(AirPlayPersistence.loadIntelSmoothSnapshot(context))
    }
}
