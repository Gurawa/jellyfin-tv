package org.jellyfin.androidtv.ui.playback.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BufferRequestDto
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupUpdate
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import timber.log.Timber
import java.time.Instant

/**
 * Manages SyncPlay functionality for synchronized playback with other users.
 * Based on the web client implementation from jellyfin-web.
 */
class SyncPlayManager(
	private val api: ApiClient,
) {
	private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private val _isEnabled = MutableStateFlow(false)
	val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

	private val _currentGroup = MutableStateFlow<GroupInfoDto?>(null)
	val currentGroup: StateFlow<GroupInfoDto?> = _currentGroup.asStateFlow()

	private val _groupState = MutableStateFlow(GroupStateType.IDLE)
	val groupState: StateFlow<GroupStateType> = _groupState.asStateFlow()

	private val _lastCommand = MutableStateFlow<SendCommand?>(null)
	val lastCommand: StateFlow<SendCommand?> = _lastCommand.asStateFlow()

	// Callback for handling playback commands
	var onPlaybackCommand: ((SyncPlayCommand) -> Unit)? = null

	// Time sync tracking
	private var serverTimeOffset: Long = 0
	private var lastPingTime: Long = 0

	init {
		subscribeToWebSocketMessages()
	}

	private fun subscribeToWebSocketMessages() {
		// Subscribe to SyncPlay commands
		api.webSocket.subscribe<SyncPlayCommandMessage>().onEach { message ->
			val command = message.data ?: return@onEach
			Timber.d("Received SyncPlay command: ${command.command}")
			_lastCommand.value = command
			handleCommand(command)
		}.launchIn(coroutineScope)

		// Subscribe to SyncPlay group updates
		api.webSocket.subscribe<SyncPlayGroupUpdateMessage>().onEach { message ->
			val update = message.data ?: return@onEach
			Timber.d("Received SyncPlay group update")
			handleGroupUpdate(update)
		}.launchIn(coroutineScope)
	}

	private fun handleCommand(command: SendCommand) {
		val whenTime = command.`when`
		val emittedAt = command.emittedAt
		val positionTicks = command.positionTicks

		when (command.command) {
			SendCommandType.UNPAUSE -> {
				onPlaybackCommand?.invoke(SyncPlayCommand.Unpause(
					whenTime = whenTime,
					positionTicks = positionTicks
				))
			}
			SendCommandType.PAUSE -> {
				onPlaybackCommand?.invoke(SyncPlayCommand.Pause(
					whenTime = whenTime,
					positionTicks = positionTicks
				))
			}
			SendCommandType.SEEK -> {
				onPlaybackCommand?.invoke(SyncPlayCommand.Seek(
					positionTicks = positionTicks ?: 0
				))
			}
			SendCommandType.STOP -> {
				onPlaybackCommand?.invoke(SyncPlayCommand.Stop)
			}
		}
	}

	private fun handleGroupUpdate(update: GroupUpdate<*>) {
		Timber.d("Group update type: ${update.type}")
		// Group updates contain information about group state changes
		// The SDK provides typed updates through GroupUpdate sealed class
	}

	/**
	 * Get available SyncPlay groups from the server.
	 */
	suspend fun getAvailableGroups(): List<GroupInfoDto> {
		return try {
			val response = api.syncPlayApi.syncPlayGetGroups()
			response.content
		} catch (e: Exception) {
			Timber.e(e, "Failed to get SyncPlay groups")
			emptyList()
		}
	}

	/**
	 * Create a new SyncPlay group with the given name.
	 */
	suspend fun createGroup(groupName: String): GroupInfoDto? {
		return try {
			val response = api.syncPlayApi.syncPlayCreateGroup(
				NewGroupRequestDto(groupName = groupName)
			)
			val group = response.content
			_currentGroup.value = group
			_isEnabled.value = true
			Timber.i("Created SyncPlay group: ${group.groupName}")
			group
		} catch (e: Exception) {
			Timber.e(e, "Failed to create SyncPlay group")
			null
		}
	}

	/**
	 * Join an existing SyncPlay group.
	 */
	suspend fun joinGroup(groupId: UUID) {
		try {
			api.syncPlayApi.syncPlayJoinGroup(
				JoinGroupRequestDto(groupId = groupId)
			)
			// Fetch group info after joining
			val groupInfo = api.syncPlayApi.syncPlayGetGroup(groupId).content
			_currentGroup.value = groupInfo
			_isEnabled.value = true
			Timber.i("Joined SyncPlay group: ${groupInfo.groupName}")
		} catch (e: Exception) {
			Timber.e(e, "Failed to join SyncPlay group")
		}
	}

	/**
	 * Leave the current SyncPlay group.
	 */
	suspend fun leaveGroup() {
		try {
			api.syncPlayApi.syncPlayLeaveGroup()
			_currentGroup.value = null
			_isEnabled.value = false
			Timber.i("Left SyncPlay group")
		} catch (e: Exception) {
			Timber.e(e, "Failed to leave SyncPlay group")
		}
	}

	/**
	 * Request play in the SyncPlay group.
	 */
	suspend fun requestPlay(itemIds: List<UUID>, startIndex: Int = 0, startPositionTicks: Long = 0) {
		try {
			api.syncPlayApi.syncPlaySetNewQueue(
				PlayRequestDto(
					playingQueue = itemIds,
					playingItemPosition = startIndex,
					startPositionTicks = startPositionTicks
				)
			)
		} catch (e: Exception) {
			Timber.e(e, "Failed to request SyncPlay play")
		}
	}

	/**
	 * Request unpause in the SyncPlay group.
	 */
	suspend fun requestUnpause() {
		try {
			api.syncPlayApi.syncPlayUnpause()
		} catch (e: Exception) {
			Timber.e(e, "Failed to request SyncPlay unpause")
		}
	}

	/**
	 * Request pause in the SyncPlay group.
	 */
	suspend fun requestPause() {
		try {
			api.syncPlayApi.syncPlayPause()
		} catch (e: Exception) {
			Timber.e(e, "Failed to request SyncPlay pause")
		}
	}

	/**
	 * Request stop in the SyncPlay group.
	 */
	suspend fun requestStop() {
		try {
			api.syncPlayApi.syncPlayStop()
		} catch (e: Exception) {
			Timber.e(e, "Failed to request SyncPlay stop")
		}
	}

	/**
	 * Request seek in the SyncPlay group.
	 */
	suspend fun requestSeek(positionTicks: Long) {
		try {
			api.syncPlayApi.syncPlaySeek(
				SeekRequestDto(positionTicks = positionTicks)
			)
		} catch (e: Exception) {
			Timber.e(e, "Failed to request SyncPlay seek")
		}
	}

	/**
	 * Notify the group that this client is buffering.
	 */
	suspend fun notifyBuffering(
		isPlaying: Boolean,
		playlistItemId: UUID,
		positionTicks: Long,
	) {
		try {
			api.syncPlayApi.syncPlayBuffering(
				BufferRequestDto(
					`when` = Instant.now(),
					positionTicks = positionTicks,
					isPlaying = isPlaying,
					playlistItemId = playlistItemId,
				)
			)
		} catch (e: Exception) {
			Timber.e(e, "Failed to notify SyncPlay buffering")
		}
	}

	/**
	 * Notify the group that this client is ready for playback.
	 */
	suspend fun notifyReady(
		isPlaying: Boolean,
		playlistItemId: UUID,
		positionTicks: Long,
	) {
		try {
			api.syncPlayApi.syncPlayReady(
				ReadyRequestDto(
					`when` = Instant.now(),
					positionTicks = positionTicks,
					isPlaying = isPlaying,
					playlistItemId = playlistItemId,
				)
			)
		} catch (e: Exception) {
			Timber.e(e, "Failed to notify SyncPlay ready")
		}
	}

	/**
	 * Send a ping to update session timing information.
	 */
	suspend fun sendPing() {
		try {
			lastPingTime = System.currentTimeMillis()
			api.syncPlayApi.syncPlayPing(
				PingRequestDto(ping = lastPingTime)
			)
		} catch (e: Exception) {
			Timber.e(e, "Failed to send SyncPlay ping")
		}
	}

	/**
	 * Calculate the position to seek to based on server time and target time.
	 */
	fun calculateSeekPosition(targetTime: Instant, positionTicks: Long?): Long {
		val now = Instant.now().toEpochMilli() + serverTimeOffset
		val targetMs = targetTime.toEpochMilli()
		val diffMs = targetMs - now
		val currentPositionTicks = positionTicks ?: 0

		// Calculate adjusted position based on time difference
		return currentPositionTicks + (diffMs * 10000) // Convert ms to ticks
	}

	fun destroy() {
		coroutineScope.cancel()
	}
}

/**
 * Sealed class representing SyncPlay commands.
 */
sealed class SyncPlayCommand {
	data class Unpause(
		val whenTime: Instant,
		val positionTicks: Long?,
	) : SyncPlayCommand()

	data class Pause(
		val whenTime: Instant,
		val positionTicks: Long?,
	) : SyncPlayCommand()

	data class Seek(
		val positionTicks: Long,
	) : SyncPlayCommand()

	data object Stop : SyncPlayCommand()
}
