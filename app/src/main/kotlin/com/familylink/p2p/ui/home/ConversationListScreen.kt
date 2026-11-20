package com.familylink.p2p.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familylink.core.data.repository.ConversationId
import com.familylink.core.p2p.transport.TransportKind
import com.familylink.p2p.FamilyLinkApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    app: FamilyLinkApp,
    onOpenChat: (String) -> Unit,
    onOpenQrPairing: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val identity by app.repository.observeIdentity().collectAsState(initial = null)
    val peers by app.repository.observePeers().collectAsState(initial = emptyList())
    val groups by app.repository.observeGroups().collectAsState(initial = emptyList())
    val coordinator by app.coordinator.collectAsState()
    val peerTransports by (coordinator?.peerTransports ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    }).collectAsState()
    val connectedRelays by (coordinator?.connectedRelays ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptySet())
    }).collectAsState()

    var showCreateGroupDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FamilyLink", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${identity?.displayName ?: ""} • ${connectedRelays.size} relays • LAN active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateGroupDialog = true }) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "New Family Group")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Network Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenQrPairing) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Pair via QR")
            }
        }
    ) { padding ->
        if (groups.isEmpty() && peers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No family contacts or groups yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap the QR button below to scan a family member's QR code, share yours, or create a Family Group.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (groups.isNotEmpty()) {
                    item {
                        Text(
                            text = "Family Groups",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(groups, key = { "g:${it.groupId}" }) { group ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onOpenChat(ConversationId.group(group.groupId))
                            },
                            leadingContent = {
                                Icon(Icons.Default.Groups, contentDescription = null)
                            },
                            headlineContent = {
                                Text(group.name, fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text("E2EE Group • Key Epoch ${group.epoch}")
                            }
                        )
                        HorizontalDivider()
                    }
                }

                if (peers.isNotEmpty()) {
                    item {
                        Text(
                            text = "Trusted Family Contacts",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(peers, key = { "p:${it.peerId}" }) { peer ->
                        val activeTransport = peerTransports[peer.peerId]
                        ListItem(
                            modifier = Modifier.clickable {
                                onOpenChat(ConversationId.direct(peer.peerId))
                            },
                            leadingContent = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            },
                            headlineContent = {
                                Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text(
                                    if (peer.verifiedViaQr) "QR Verified • ${peer.peerId.take(10)}…"
                                    else "Peer • ${peer.peerId.take(10)}…"
                                )
                            },
                            trailingContent = {
                                TransportBadge(activeTransport)
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showCreateGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        val selectedPeerIds = remember { mutableStateListOf<String>() }

        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            title = { Text("Create Family Group") },
            text = {
                Column {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Group Name (e.g., Smith Family)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (peers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Add existing contacts (or invite via Group QR later):", style = MaterialTheme.typography.labelMedium)
                        peers.forEach { peer ->
                            val checked = peer.peerId in selectedPeerIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selectedPeerIds.remove(peer.peerId)
                                        else selectedPeerIds.add(peer.peerId)
                                    }
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        if (it) selectedPeerIds.add(peer.peerId)
                                        else selectedPeerIds.remove(peer.peerId)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(peer.displayName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = groupName.isNotBlank(),
                    onClick = {
                        showCreateGroupDialog = false
                        scope.launch {
                            val group = app.repository.createFamilyGroup(
                                name = groupName,
                                initialMemberPeerIds = selectedPeerIds.toList()
                            )
                            coordinator?.refreshRelays()
                            onOpenChat(ConversationId.group(group.groupId))
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TransportBadge(kind: TransportKind?) {
    val label = kind?.label ?: "Offline"
    val containerColor = when (kind) {
        TransportKind.LAN -> MaterialTheme.colorScheme.primaryContainer
        TransportKind.WIFI_DIRECT -> MaterialTheme.colorScheme.tertiaryContainer
        TransportKind.RELAY -> MaterialTheme.colorScheme.secondaryContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
