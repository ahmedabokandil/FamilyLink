package com.familylink.p2p.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familylink.core.crypto.IdentityManager
import com.familylink.core.crypto.QrPayloadCodec
import com.familylink.core.data.db.MessageEntity
import com.familylink.core.data.db.MessageStatus
import com.familylink.core.data.db.ReactionEntity
import com.familylink.core.data.repository.ConversationId
import com.familylink.p2p.FamilyLinkApp
import com.familylink.p2p.media.VoiceNotePlayer
import com.familylink.p2p.media.VoiceNoteRecorder
import com.familylink.p2p.ui.home.TransportBadge
import com.familylink.p2p.ui.qr.QrBitmapGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val QUICK_EMOJIS = listOf("👍", "❤️", "😂", "😮", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    app: FamilyLinkApp,
    conversationId: String,
    onBack: () -> Unit,
    onStartCall: ((conversationId: String, video: Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isGroup = ConversationId.isGroup(conversationId)
    val rawId = ConversationId.extractId(conversationId)

    val identity by app.repository.observeIdentity().collectAsState(initial = null)
    val peer by (if (!isGroup) app.repository.observePeer(rawId) else remember {
        kotlinx.coroutines.flow.flowOf(null)
    }).collectAsState(initial = null)
    val group by (if (isGroup) app.repository.observeGroup(rawId) else remember {
        kotlinx.coroutines.flow.flowOf(null)
    }).collectAsState(initial = null)
    val members by (if (isGroup) app.repository.observeGroupMembers(rawId) else remember {
        kotlinx.coroutines.flow.flowOf(emptyList())
    }).collectAsState(initial = emptyList())
    val messages by app.repository.observeMessages(conversationId).collectAsState(initial = emptyList())
    val reactions by app.repository.observeReactions(conversationId).collectAsState(initial = emptyList())
    val coordinator by app.coordinator.collectAsState()
    val peerTransports by (coordinator?.peerTransports ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    }).collectAsState()
    val typingMap by (coordinator?.typingIndicators ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    }).collectAsState()
    val transferProgress by (coordinator?.transferProgress ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    }).collectAsState()

    val typingNames = typingMap[conversationId].orEmpty()
    val reactionsByMsg = remember(reactions) { reactions.groupBy { it.targetPacketId } }

    var composerText by remember { mutableStateOf("") }
    var lastTypingSentMs by remember { mutableLongStateOf(0L) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var playingAudioPath by remember { mutableStateOf<String?>(null) }
    var reactionTargetMsg by remember { mutableStateOf<MessageEntity?>(null) }

    var showSafetyDialog by remember { mutableStateOf(false) }
    var showGroupQrDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }
    var groupInviteUri by remember { mutableStateOf("") }

    val voiceRecorder = remember { VoiceNoteRecorder(context) }
    val voicePlayer = remember { VoiceNotePlayer() }
    DisposableEffect(Unit) {
        onDispose {
            voiceRecorder.cancel()
            voicePlayer.stop()
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRecordingVoice = voiceRecorder.startRecording()
        }
    }

    fun sendUriAttachment(uri: Uri) {
        scope.launch {
            val copied = withContext(Dispatchers.IO) { copyUriToLocalMedia(context, uri) } ?: return@launch
            coordinator?.sendAttachment(
                conversationId = conversationId,
                localFile = copied.first,
                fileName = copied.second,
                mimeType = copied.third
            )
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) sendUriAttachment(uri)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) sendUriAttachment(uri)
    }

    // Send READ receipts for newly viewed incoming messages
    LaunchedEffect(messages.size) {
        val unreadIncoming = messages.filter { !it.isOutgoing && it.status != MessageStatus.READ }
        unreadIncoming.forEach { msg ->
            app.repository.updateMessageStatus(msg.packetId, MessageStatus.READ)
            coordinator?.sendReadReceipt(conversationId, msg.packetId, msg.senderPk)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isGroup) (group?.name ?: "Family Group") else (peer?.displayName ?: "Contact"),
                            fontWeight = FontWeight.Bold
                        )
                        if (typingNames.isNotEmpty()) {
                            Text(
                                text = "${typingNames.joinToString(", ")} typing…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (isGroup) {
                            Text(
                                text = "${members.size} members • Epoch ${group?.epoch ?: 1} E2EE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            TransportBadge(peerTransports[rawId])
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onStartCall != null) {
                        IconButton(onClick = { onStartCall(conversationId, false) }) {
                            Icon(Icons.Default.Call, contentDescription = "Voice Call")
                        }
                        IconButton(onClick = { onStartCall(conversationId, true) }) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video Call")
                        }
                    }
                    if (isGroup) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val me = identity ?: return@launch
                                    val g = group ?: return@launch
                                    val relays = app.repository.getEnabledRelayUrls()
                                    val lan = coordinator?.lanTransport?.getLocalLanHint()
                                    groupInviteUri = QrPayloadCodec.encodeGroupInviteUri(
                                        adminIdentity = me.toKeyPair(),
                                        adminDisplayName = me.displayName,
                                        groupId = g.groupId,
                                        epoch = g.epoch,
                                        groupKeyBase64 = g.groupKeyBase64,
                                        groupName = g.name,
                                        relays = relays,
                                        lanHint = lan
                                    )
                                    showGroupQrDialog = true
                                }
                            }
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = "Group Invite QR")
                        }
                        IconButton(onClick = { showMembersDialog = true }) {
                            Icon(Icons.Default.Groups, contentDescription = "Group Members")
                        }
                    } else {
                        IconButton(onClick = { showSafetyDialog = true }) {
                            Icon(Icons.Default.Security, contentDescription = "Verify Safety Number")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(messages, key = { it.packetId }) { msg ->
                    MessageBubble(
                        message = msg,
                        showSenderName = isGroup && !msg.isOutgoing,
                        reactions = reactionsByMsg[msg.packetId].orEmpty(),
                        transferFraction = msg.fileId?.let { transferProgress[it] },
                        isPlayingAudio = playingAudioPath != null && playingAudioPath == msg.mediaPath,
                        onTogglePlayAudio = { path ->
                            val nowPlaying = voicePlayer.togglePlay(path) {
                                playingAudioPath = null
                            }
                            playingAudioPath = if (nowPlaying) path else null
                        },
                        onLongPress = { reactionTargetMsg = msg }
                    )
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                ) {
                    Icon(Icons.Default.Photo, contentDescription = "Send Photo or Video")
                }
                IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach File")
                }

                OutlinedTextField(
                    value = composerText,
                    onValueChange = { updated ->
                        composerText = updated
                        val now = System.currentTimeMillis()
                        if (updated.isNotBlank() && now - lastTypingSentMs > 3_000L) {
                            lastTypingSentMs = now
                            scope.launch { coordinator?.sendTypingIndicator(conversationId, true) }
                        }
                    },
                    placeholder = { Text(if (isRecordingVoice) "Recording voice note..." else "Message...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )

                if (composerText.isBlank()) {
                    IconButton(
                        onClick = {
                            if (isRecordingVoice) {
                                isRecordingVoice = false
                                val recorded = voiceRecorder.stopRecording()
                                if (recorded != null) {
                                    scope.launch {
                                        coordinator?.sendAttachment(
                                            conversationId = conversationId,
                                            localFile = recorded.file,
                                            fileName = recorded.file.name,
                                            mimeType = "audio/mp4",
                                            isVoiceNote = true,
                                            durationMs = recorded.durationMs
                                        )
                                    }
                                }
                            } else {
                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isRecordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecordingVoice) "Stop & Send Voice Note" else "Record Voice Note",
                            tint = if (isRecordingVoice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val toSend = composerText
                            composerText = ""
                            scope.launch {
                                coordinator?.sendTypingIndicator(conversationId, false)
                                if (isGroup) {
                                    coordinator?.sendGroupTextMessage(rawId, toSend)
                                } else {
                                    coordinator?.sendDirectTextMessage(rawId, toSend)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (reactionTargetMsg != null) {
        val target = reactionTargetMsg!!
        AlertDialog(
            onDismissRequest = { reactionTargetMsg = null },
            title = { Text("React to Message") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QUICK_EMOJIS.forEach { emoji ->
                        TextButton(
                            onClick = {
                                reactionTargetMsg = null
                                scope.launch {
                                    coordinator?.sendReaction(conversationId, target.packetId, emoji)
                                }
                            }
                        ) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reactionTargetMsg = null }) { Text("Cancel") }
            }
        )
    }

    if (showSafetyDialog && identity != null && peer != null) {
        val safetyNumber = remember(identity?.peerId, peer?.peerId) {
            IdentityManager.computeSafetyNumber(identity!!.peerId, peer!!.peerId)
        }
        AlertDialog(
            onDismissRequest = { showSafetyDialog = false },
            title = { Text("E2EE Safety Number") },
            text = {
                Column {
                    Text("Compare this 60-digit number with ${peer!!.displayName} in person or on a voice call:")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = safetyNumber,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafetyDialog = false }) { Text("Done") }
            }
        )
    }

    if (showGroupQrDialog && groupInviteUri.isNotBlank()) {
        val qrBitmap = remember(groupInviteUri) { QrBitmapGenerator.generate(groupInviteUri) }
        AlertDialog(
            onDismissRequest = { showGroupQrDialog = false },
            title = { Text("Family Group Invite QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Family members can scan this QR code or paste the invite URI to join '${group?.name}'.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Group Invite QR",
                        modifier = Modifier.size(220.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Group Invite", groupInviteUri))
                        }
                    ) {
                        Text("Copy Group Invite URI")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupQrDialog = false }) { Text("Close") }
            }
        )
    }

    if (showMembersDialog && group != null) {
        val isAdmin = identity?.peerId == group?.adminPeerId
        AlertDialog(
            onDismissRequest = { showMembersDialog = false },
            title = { Text("Family Members (${members.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.forEach { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(member.displayName, fontWeight = FontWeight.Medium)
                                Text(member.role, style = MaterialTheme.typography.labelSmall)
                            }
                            if (isAdmin && member.peerId != identity?.peerId) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            coordinator?.removeGroupMemberAndDistributeRotatedKey(
                                                group!!.groupId,
                                                member.peerId
                                            )
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.PersonRemove, contentDescription = "Remove & Rotate Key")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMembersDialog = false }) { Text("Close") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    showSenderName: Boolean,
    reactions: List<ReactionEntity>,
    transferFraction: Float?,
    isPlayingAudio: Boolean,
    onTogglePlayAudio: (String) -> Unit,
    onLongPress: () -> Unit
) {
    val timeText = remember(message.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestampMs))
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (message.isOutgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSenderName) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Inline photo preview if received/sent image exists locally
                val path = message.mediaPath
                if (path != null && message.mediaMime?.startsWith("image/") == true) {
                    val bmp = remember(path) {
                        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = message.mediaName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                // Inline Voice Note player
                if (path != null && message.mediaMime?.startsWith("audio/") == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onTogglePlayAudio(path) }) {
                            Icon(
                                imageVector = if (isPlayingAudio) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = "Play voice note"
                            )
                        }
                        Text(message.bodyText, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        text = message.bodyText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (transferFraction != null && transferFraction < 1f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { transferFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (message.isOutgoing) "$timeText • ${message.status}" else timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (reactions.isNotEmpty()) {
            val counts = reactions.groupingBy { it.emoji }.eachCount()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                counts.forEach { (emoji, count) ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = if (count > 1) "$emoji $count" else emoji,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun copyUriToLocalMedia(context: Context, uri: Uri): Triple<File, String, String>? {
    return try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var displayName = "file_${UUID.randomUUID().toString().take(8)}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                }
            }
        }
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(mediaDir, "${UUID.randomUUID().toString().take(6)}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        Triple(dest, displayName, mime)
    } catch (_: Exception) {
        null
    }
}
