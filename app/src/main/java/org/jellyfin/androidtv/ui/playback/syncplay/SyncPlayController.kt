package org.jellyfin.androidtv.ui.playback.syncplay

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.playback.PlaybackController
import timber.log.Timber
import java.time.Instant

/**
 * Service that connects SyncPlay commands to the PlaybackController.
 * This handles the synchronization of playback actions with the SyncPlay group.
 */
class SyncPlayController(
	private val syncPlayManager: SyncPlayManager,
) {
	private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val mainHandler = Handler(Looper.getMainLooper())

	private var playbackController: PlaybackController? = null
	private var isActive = false

	/**
	 * Attach this controller to a PlaybackController.
	 */
	fun attach(controller: PlaybackController) {
		playbackController = controller
		isActive = true

		// Set up the command handler
		syncPlayManager.onPlaybackCommand = { command ->
			handleCommand(command)
		}

		// Observe SyncPlay enabled state
		syncPlayManager.isEnabled.onEach { enabled ->
			Timber.d("SyncPlay enabled state changed: $enabled")
		}.launchIn(coroutineScope)
	}

	/**
	 * Detach from the current PlaybackController.
	 */
	fun detach() {
		isActive = false
		syncPlayManager.onPlaybackCommand = null
		playbackController = null
	}

	private fun handleCommand(command: SyncPlayCommand) {
		val controller = playbackController ?: return
		if (!isActive) return

		mainHandler.post {
			when (command) {
				is SyncPlayCommand.Unpause -> handleUnpause(controller, command)
				is SyncPlayCommand.Pause -> handlePause(controller, command)
				is SyncPlayCommand.Seek -> handleSeek(controller, command)
				is SyncPlayCommand.Stop -> handleStop(controller)
			}
		}
	}

	private fun handleUnpause(controller: PlaybackController, command: SyncPlayCommand.Unpause) {
		Timber.d("SyncPlay: Handling unpause command")

		// Calculate the delay until we should start playing
		val now = Instant.now().toEpochMilli()
		val targetTime = command.whenTime.toEpochMilli()
		val delay = targetTime - now

		if (delay > 0) {
			// Schedule the play action for the target time
			val targetPosition = command.positionTicks?.div(10000) ?: controller.currentPosition
			mainHandler.postDelayed({
				if (isActive && playbackController != null) {
					if (controller.isPaused) {
						controller.play(targetPosition)
					}
				}
			}, delay)
		} else {
			// Target time has passed, play immediately with adjusted position
			val elapsedMs = -delay
			val positionTicks = command.positionTicks ?: 0
			val adjustedPositionMs = (positionTicks / 10000) + elapsedMs

			if (controller.isPaused) {
				controller.play(adjustedPositionMs)
			} else {
				// Already playing, just seek to correct position
				controller.seek(adjustedPositionMs)
			}
		}
	}

	private fun handlePause(controller: PlaybackController, command: SyncPlayCommand.Pause) {
		Timber.d("SyncPlay: Handling pause command")

		val now = Instant.now().toEpochMilli()
		val targetTime = command.whenTime.toEpochMilli()
		val delay = targetTime - now

		if (delay > 0) {
			// Schedule the pause action for the target time
			mainHandler.postDelayed({
				if (isActive && playbackController != null) {
					if (controller.isPlaying) {
						controller.pause()
					}
					// Seek to the exact position after pausing
					command.positionTicks?.let { ticks ->
						controller.seek(ticks / 10000)
					}
				}
			}, delay)
		} else {
			// Target time has passed, pause immediately
			if (controller.isPlaying) {
				controller.pause()
			}
			command.positionTicks?.let { ticks ->
				controller.seek(ticks / 10000)
			}
		}
	}

	private fun handleSeek(controller: PlaybackController, command: SyncPlayCommand.Seek) {
		Timber.d("SyncPlay: Handling seek command to ${command.positionTicks} ticks")
		val positionMs = command.positionTicks / 10000
		controller.seek(positionMs)
	}

	private fun handleStop(controller: PlaybackController) {
		Timber.d("SyncPlay: Handling stop command")
		controller.stop()
	}

	/**
	 * Notify the SyncPlay group of a user-initiated action.
	 * This should be called before executing user actions to propagate them to the group.
	 */
	fun notifyPlayPause() {
		if (!syncPlayManager.isEnabled.value) return

		coroutineScope.launch(Dispatchers.IO) {
			val controller = playbackController ?: return@launch
			if (controller.isPlaying) {
				syncPlayManager.requestPause()
			} else {
				syncPlayManager.requestUnpause()
			}
		}
	}

	fun notifySeek(positionMs: Long) {
		if (!syncPlayManager.isEnabled.value) return

		coroutineScope.launch(Dispatchers.IO) {
			syncPlayManager.requestSeek(positionMs * 10000)
		}
	}

	fun notifyStop() {
		if (!syncPlayManager.isEnabled.value) return

		coroutineScope.launch(Dispatchers.IO) {
			syncPlayManager.requestStop()
		}
	}
}
