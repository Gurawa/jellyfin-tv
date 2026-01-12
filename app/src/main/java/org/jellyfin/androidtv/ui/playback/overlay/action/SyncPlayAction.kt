package org.jellyfin.androidtv.ui.playback.overlay.action

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.ui.playback.syncplay.SyncPlayManager
import org.jellyfin.sdk.model.api.GroupInfoDto

class SyncPlayAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
	private val syncPlayManager: SyncPlayManager,
) : CustomAction(context, customPlaybackTransportControlGlue) {

	private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	init {
		initializeWithIcon(R.drawable.ic_sync_play)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		if (syncPlayManager.isEnabled.value) {
			// Already in a group, show leave dialog
			showLeaveGroupDialog(context)
		} else {
			// Not in a group, show join/create dialog
			showGroupSelectionDialog(context)
		}
	}

	private fun showLeaveGroupDialog(context: Context) {
		val currentGroup = syncPlayManager.currentGroup.value
		val groupName = currentGroup?.groupName ?: context.getString(R.string.syncplay_group)

		AlertDialog.Builder(context)
			.setTitle(R.string.syncplay_leave_group)
			.setMessage(context.getString(R.string.syncplay_leave_group_message, groupName))
			.setPositiveButton(R.string.lbl_yes) { _, _ ->
				coroutineScope.launch {
					syncPlayManager.leaveGroup()
				}
			}
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}

	private fun showGroupSelectionDialog(context: Context) {
		coroutineScope.launch {
			val groups = withContext(Dispatchers.IO) {
				syncPlayManager.getAvailableGroups()
			}

			if (groups.isEmpty()) {
				// No groups available, show create dialog directly
				showCreateGroupDialog(context)
			} else {
				// Show list of groups + create option
				showGroupListDialog(context, groups)
			}
		}
	}

	private fun showGroupListDialog(context: Context, groups: List<GroupInfoDto>) {
		val groupNames = groups.map { group ->
			"${group.groupName} (${group.participants.size} ${context.getString(R.string.syncplay_participants)})"
		}.toMutableList()
		groupNames.add(context.getString(R.string.syncplay_create_new_group))

		AlertDialog.Builder(context)
			.setTitle(R.string.syncplay_select_group)
			.setItems(groupNames.toTypedArray()) { _, which ->
				if (which < groups.size) {
					// Join existing group
					val selectedGroup = groups[which]
					coroutineScope.launch {
						syncPlayManager.joinGroup(selectedGroup.groupId)
					}
				} else {
					// Create new group
					showCreateGroupDialog(context)
				}
			}
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}

	private fun showCreateGroupDialog(context: Context) {
		val input = EditText(context)
		input.hint = context.getString(R.string.syncplay_group_name_hint)

		AlertDialog.Builder(context)
			.setTitle(R.string.syncplay_create_group)
			.setView(input)
			.setPositiveButton(R.string.lbl_ok) { _, _ ->
				val groupName = input.text.toString().trim()
				if (groupName.isNotEmpty()) {
					coroutineScope.launch {
						syncPlayManager.createGroup(groupName)
					}
				}
			}
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}
}
