package com.familylink.p2p.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familylink.core.data.db.RelayConfigEntity
import com.familylink.core.data.db.RelayType
import com.familylink.p2p.FamilyLinkApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    app: FamilyLinkApp,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val identity by app.repository.observeIdentity().collectAsState(initial = null)
    val relays by app.repository.observeRelays().collectAsState(initial = emptyList())
    val coordinator by app.coordinator.collectAsState()
    val connectedRelays by (coordinator?.connectedRelays ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptySet())
    }).collectAsState()

    var newUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("P2P Network & Relays") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Local Node Status", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "PeerId: ${identity?.peerId?.take(24) ?: ""}…",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "LAN Listener: ${coordinator?.lanTransport?.getLocalLanHint() ?: "Starting..."}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Connected Public Relays: ${connectedRelays.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { coordinator?.wifiDirectTransport?.triggerDiscovery() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Scan Nearby Wi-Fi Direct Devices")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onOpenBackup,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Identity Backup & 33-Word Recovery")
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Decentralized Relays & STUN/TURN Servers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        placeholder = { Text("wss://relay.example.org or stun:...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = newUrl.startsWith("wss://") || newUrl.startsWith("ws://") ||
                            newUrl.startsWith("stun:") || newUrl.startsWith("turn:"),
                        onClick = {
                            val trimmed = newUrl.trim()
                            val type = when {
                                trimmed.startsWith("stun:") -> RelayType.STUN
                                trimmed.startsWith("turn:") -> RelayType.TURN
                                else -> RelayType.RELAY
                            }
                            newUrl = ""
                            scope.launch {
                                app.repository.upsertRelay(RelayConfigEntity(trimmed, type, true))
                                coordinator?.refreshRelays()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
            }

            items(relays, key = { it.url }) { cfg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cfg.url, style = MaterialTheme.typography.bodyMedium)
                            val live = cfg.url in connectedRelays
                            Text(
                                text = "${cfg.type}${if (live) " • Connected" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (live) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    app.repository.upsertRelay(cfg.copy(enabled = enabled))
                                    coordinator?.refreshRelays()
                                }
                            }
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    app.repository.deleteRelay(cfg.url)
                                    coordinator?.refreshRelays()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }
}
