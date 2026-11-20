package com.familylink.p2p.ui.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familylink.core.crypto.ParsedQrInvite
import com.familylink.core.crypto.QrPayloadCodec
import com.familylink.core.data.repository.ConversationId
import com.familylink.p2p.FamilyLinkApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPairingScreen(
    app: FamilyLinkApp,
    initialInviteUri: String? = null,
    onBack: () -> Unit,
    onPairedOpenConversation: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val identity by app.repository.observeIdentity().collectAsState(initial = null)
    val coordinator by app.coordinator.collectAsState()
    var selectedTab by remember { mutableIntStateOf(if (initialInviteUri != null) 1 else 0) }
    var myQrUri by remember { mutableStateOf("") }
    var pastedUri by remember { mutableStateOf(initialInviteUri.orEmpty()) }
    var handlingScan by remember { mutableStateOf(false) }

    LaunchedEffect(identity, coordinator) {
        val me = identity ?: return@LaunchedEffect
        val relays = app.repository.getEnabledRelayUrls()
        val lanHint = coordinator?.lanTransport?.getLocalLanHint()
        myQrUri = QrPayloadCodec.encodeContactUri(
            identity = me.toKeyPair(),
            displayName = me.displayName,
            relays = relays,
            lanHint = lanHint
        )
    }

    fun handleInviteUri(rawUri: String) {
        if (handlingScan) return
        handlingScan = true
        scope.launch {
            try {
                when (val parsed = QrPayloadCodec.parseAndVerify(rawUri)) {
                    is ParsedQrInvite.Contact -> {
                        val peer = app.repository.addContactFromQr(parsed.payload)
                        if (!peer.lastLanHost.isNullOrBlank() && peer.lastLanPort != null) {
                            coordinator?.lanTransport?.registerPeerHint(
                                peer.peerId,
                                peer.lastLanHost!!,
                                peer.lastLanPort!!
                            )
                        }
                        coordinator?.refreshRelays()
                        coordinator?.sendPeerHello(peer.peerId)
                        onPairedOpenConversation(ConversationId.direct(peer.peerId))
                    }
                    is ParsedQrInvite.Group -> {
                        val group = app.repository.joinGroupFromQr(parsed.payload)
                        coordinator?.announceGroupJoin(group.groupId)
                        onPairedOpenConversation(ConversationId.group(group.groupId))
                    }
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Invalid QR/Invite: ${e.message}")
                handlingScan = false
            }
        }
    }

    LaunchedEffect(initialInviteUri) {
        if (!initialInviteUri.isNullOrBlank()) {
            handleInviteUri(initialInviteUri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR Pairing & Invites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("My Contact QR") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Scan / Paste Invite") }
                )
            }

            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = identity?.displayName ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (myQrUri.isNotBlank()) {
                        val bitmap = remember(myQrUri) { QrBitmapGenerator.generate(myQrUri) }
                        Card {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "My FamilyLink QR Code",
                                modifier = Modifier
                                    .size(260.dp)
                                    .padding(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("FamilyLink Invite", myQrUri))
                                    scope.launch { snackbarHostState.showSnackbar("Invite URI copied") }
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy URI")
                            }
                            Button(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, myQrUri)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share FamilyLink Invite"))
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share Remote")
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        QrCameraScanner(
                            onQrScanned = { scanned ->
                                if (scanned.startsWith("famlink://")) {
                                    handleInviteUri(scanned)
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Or paste a remote Contact / Group invite URI:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pastedUri,
                            onValueChange = { pastedUri = it },
                            placeholder = { Text("famlink://peer/v1?...") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            enabled = pastedUri.isNotBlank(),
                            onClick = { handleInviteUri(pastedUri) }
                        ) {
                            Text("Pair")
                        }
                    }
                }
            }
        }
    }
}
