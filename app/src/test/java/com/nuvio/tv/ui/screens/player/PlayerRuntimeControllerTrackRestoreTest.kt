package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerRuntimeControllerTrackRestoreTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun selectedInternalSubtitleIsReappliedAndPendingPreferenceIsConsumedOnce() {
        val fixture = fixture(subtitleSelectedInSnapshot = true)

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = fixture.subtitleTracks
        )

        assertEquals(0, fixture.uiState.value.selectedSubtitleTrackIndex)
        assertNull(fixture.pendingPreference())
        assertNotNull(fixture.appliedParameters())
        assertTrue(fixture.appliedParameters()!!.overrides.containsKey(fixture.mediaTrackGroup))

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = fixture.subtitleTracks
        )

        verify(exactly = 1) { fixture.player.trackSelectionParameters = any() }
    }

    @Test
    fun unselectedInternalSubtitleContinuesToBeAppliedAndConsumed() {
        val fixture = fixture(subtitleSelectedInSnapshot = false)

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = fixture.subtitleTracks
        )

        assertEquals(0, fixture.uiState.value.selectedSubtitleTrackIndex)
        assertNull(fixture.pendingPreference())
        verify(exactly = 1) { fixture.player.trackSelectionParameters = any() }
    }

    @Test
    fun selectedEngineSwitchSubtitleKeepsPendingPipelineRestartBehavior() {
        val fixture = fixture(
            subtitleSelectedInSnapshot = true,
            useEngineSwitchPreference = true
        )

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = fixture.subtitleTracks
        )

        assertEquals(0, fixture.uiState.value.selectedSubtitleTrackIndex)
        assertNotNull(fixture.pendingEngineSwitchPreference())
        verify(exactly = 0) { fixture.player.trackSelectionParameters = any() }
    }

    @Test
    fun internalSubtitleRestoreIsDeferredUntilTracksAreAvailable() {
        val fixture = fixture(subtitleSelectedInSnapshot = false)
        val pending = fixture.pendingPreference()

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = emptyList()
        )

        assertSame(pending, fixture.pendingPreference())
        verify(exactly = 0) { fixture.player.trackSelectionParameters = any() }
    }

    @Test
    fun unmatchedInternalSubtitleDoesNotSelectAnUnrelatedTrack() {
        val fixture = fixture(
            subtitleSelectedInSnapshot = false,
            rememberedTrack = PlayerRuntimeController.RememberedTrackSelection(
                language = "spa",
                name = "Spanish",
                trackId = "subtitle-es"
            )
        )

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = fixture.subtitleTracks
        )

        assertNull(fixture.pendingPreference())
        verify(exactly = 0) { fixture.player.trackSelectionParameters = any() }
    }

    @Test
    fun disabledSubtitlePreferenceStaysDisabled() {
        val fixture = fixture(
            subtitleSelectedInSnapshot = false,
            subtitlePreference = PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        )

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = fixture.subtitleTracks
        )

        assertEquals(-1, fixture.uiState.value.selectedSubtitleTrackIndex)
        assertNull(fixture.pendingPreference())
        verify(exactly = 0) { fixture.player.trackSelectionParameters = any() }
    }

    @Test
    fun addonSubtitleRestoreDoesNotSelectAnInternalTrack() {
        val addon = Subtitle(
            id = "addon-subtitle",
            url = "https://example.com/subtitle.srt",
            lang = "eng",
            addonName = "Subtitle Addon",
            addonLogo = null
        )
        val fixture = fixture(
            subtitleSelectedInSnapshot = false,
            subtitlePreference = PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                id = addon.id,
                url = addon.url,
                language = addon.lang,
                addonName = addon.addonName
            ),
            addonSubtitles = listOf(addon)
        )
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTrackSelectionKt")
        every { fixture.controller.selectAddonSubtitle(addon) } just Runs

        fixture.controller.applyPersistedTrackPreference(
            audioTracks = emptyList(),
            subtitleTracks = fixture.subtitleTracks
        )

        assertEquals(-1, fixture.uiState.value.selectedSubtitleTrackIndex)
        assertEquals(addon, fixture.uiState.value.selectedAddonSubtitle)
        assertNull(fixture.pendingPreference())
        verify(exactly = 1) { fixture.controller.selectAddonSubtitle(addon) }
        verify(exactly = 0) { fixture.player.trackSelectionParameters = any() }
    }

    private fun fixture(
        subtitleSelectedInSnapshot: Boolean,
        rememberedTrack: PlayerRuntimeController.RememberedTrackSelection =
            PlayerRuntimeController.RememberedTrackSelection(
                language = "eng",
                name = "English",
                trackId = "subtitle-en"
            ),
        subtitlePreference: PlayerRuntimeController.RememberedSubtitleSelection =
            PlayerRuntimeController.RememberedSubtitleSelection.Internal(rememberedTrack),
        addonSubtitles: List<Subtitle> = emptyList(),
        useEngineSwitchPreference: Boolean = false
    ): RestoreFixture {
        val format = Format.Builder()
            .setId("subtitle-en")
            .setLabel("English")
            .setLanguage("eng")
            .build()
        val mediaTrackGroup = TrackGroup(format)
        val mediaTracks = Tracks(
            ImmutableList.of(
                Tracks.Group(
                    mediaTrackGroup,
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(subtitleSelectedInSnapshot)
                )
            )
        )
        val subtitleTracks = listOf(
            TrackInfo(
                index = 0,
                name = "English",
                language = "eng",
                trackId = "subtitle-en",
                isSelected = subtitleSelectedInSnapshot
            )
        )
        val uiState = MutableStateFlow(
            PlayerUiState(
                subtitleTracks = subtitleTracks,
                selectedSubtitleTrackIndex = if (subtitleSelectedInSnapshot) 0 else -1,
                addonSubtitles = addonSubtitles
            )
        )
        val player = mockk<ExoPlayer>(relaxed = true)
        val appliedParameters = slot<TrackSelectionParameters>()
        every { player.currentTracks } returns mediaTracks
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        every { player.trackSelectionParameters = capture(appliedParameters) } just Runs

        val trackPreference = PlayerRuntimeController.TrackPreference(subtitle = subtitlePreference)
        var pendingPreference: PlayerRuntimeController.TrackPreference? =
            trackPreference.takeUnless { useEngineSwitchPreference }
        var pendingEngineSwitchPreference: PlayerRuntimeController.PendingEngineSwitchTrackPreference? =
            trackPreference.takeIf { useEngineSwitchPreference }?.let {
                PlayerRuntimeController.PendingEngineSwitchTrackPreference(
                    streamUrl = "https://example.com/video.mkv",
                    preference = it,
                    sourceEngine = InternalPlayerEngine.MVP_PLAYER
                )
            }
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        every { controller._uiState } returns uiState
        every { controller._exoPlayer } returns player
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.currentStreamUrl } returns "https://example.com/video.mkv"
        every { controller.pendingEngineSwitchTrackPreference } answers {
            pendingEngineSwitchPreference
        }
        every { controller.pendingEngineSwitchTrackPreference = any() } answers {
            pendingEngineSwitchPreference = firstArg()
        }
        every { controller.persistedTrackPreference } answers { pendingPreference }
        every { controller.persistedTrackPreference = any() } answers {
            pendingPreference = firstArg()
        }

        return RestoreFixture(
            controller = controller,
            player = player,
            mediaTrackGroup = mediaTrackGroup,
            subtitleTracks = subtitleTracks,
            uiState = uiState,
            pendingPreference = { pendingPreference },
            pendingEngineSwitchPreference = { pendingEngineSwitchPreference },
            appliedParameters = {
                if (appliedParameters.isCaptured) appliedParameters.captured else null
            }
        )
    }

    private data class RestoreFixture(
        val controller: PlayerRuntimeController,
        val player: ExoPlayer,
        val mediaTrackGroup: TrackGroup,
        val subtitleTracks: List<TrackInfo>,
        val uiState: MutableStateFlow<PlayerUiState>,
        val pendingPreference: () -> PlayerRuntimeController.TrackPreference?,
        val pendingEngineSwitchPreference: () -> PlayerRuntimeController.PendingEngineSwitchTrackPreference?,
        val appliedParameters: () -> TrackSelectionParameters?
    )
}
