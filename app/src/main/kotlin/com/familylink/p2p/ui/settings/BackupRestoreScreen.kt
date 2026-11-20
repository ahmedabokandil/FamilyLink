package com.familylink.p2p.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.familylink.core.crypto.BackupCrypto
import com.familylink.p2p.FamilyLinkApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    app: FamilyLinkApp,
    onBack: () -> Unit,
    onRestoredRestartHome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val identity by app.repository.observeIdentity().collectAsState(initial = null)

    var exportPassphrase by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var showPhrase by remember { mutableStateOf(false) }
    var restorePhraseInput by remember { mutableStateOf("") }
    var restoreDisplayName by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val encrypted = withContext(Dispatchers.Default) {
                    app.repository.exportEncryptedBackup(exportPassphrase)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(encrypted.toByteArray(Charsets.UTF_8))
                    }
                }
                exportPassphrase = ""
                snackbarHostState.showSnackbar("Encrypted .flbackup exported successfully")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Export failed: ${e.message}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                } ?: throw IllegalStateException("Could not read backup file")
                val restored = withContext(Dispatchers.Default) {
                    app.repository.importEncryptedBackup(raw, importPassphrase)
                }
                app.ensureP2pStarted(restored.peerId)
                importPassphrase = ""
                snackbarHostState.showSnackbar("Restored identity & groups for ${restored.displayName}")
                onRestoredRestartHome()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Import failed: ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity Backup & Recovery") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (identity != null) {
                // Encrypted .flbackup export
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Export Encrypted Backup (.flbackup)", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Saves your identity keys, trusted contacts, family groups, and relays protected with Argon2id + ChaCha20-Poly1305.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = exportPassphrase,
                            onValueChange = { exportPassphrase = it },
                            label = { Text("Backup Passphrase (min 6 chars)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            enabled = exportPassphrase.length >= 6,
                            onClick = { exportLauncher.launch("familylink_backup.flbackup") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export .flbackup File")
                        }
                    }
                }

                // 33-word Seed Recovery Phrase
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Master Seed Recovery Phrase", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Write these 33 checksummed words on paper to recover your exact Ed25519 + X25519 PeerId on a new phone.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val phrase = remember(identity?.ed25519PrivateKey) {
                            BackupCrypto.encodeRecoveryPhrase(identity!!.ed25519PrivateKey)
                        }
                        if (showPhrase) {
                            Text(
                                text = phrase,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showPhrase = false }) { Text("Hide") }
                                Button(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Recovery Phrase", phrase))
                                        scope.launch { snackbarHostState.showSnackbar("Recovery phrase copied") }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy")
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { showPhrase = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reveal Recovery Phrase")
                            }
                        }
                    }
                }
            }

            // Import .flbackup
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Import Encrypted Backup (.flbackup)", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importPassphrase,
                        onValueChange = { importPassphrase = it },
                        label = { Text("Backup Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        enabled = importPassphrase.isNotBlank(),
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select .flbackup & Restore")
                    }
                }
            }

            // Restore from 33-word phrase
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Recover Identity from 33-Word Phrase", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restoreDisplayName,
                        onValueChange = { restoreDisplayName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restorePhraseInput,
                        onValueChange = { restorePhraseInput = it },
                        label = { Text("33 Recovery Words (space separated)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = restorePhraseInput.isNotBlank() && restoreDisplayName.isNotBlank(),
                        onClick = {
                            scope.launch {
                                try {
                                    val restored = app.repository.restoreIdentityFromPhrase(
                                        restoreDisplayName,
                                        restorePhraseInput
                                    )
                                    app.ensureP2pStarted(restored.peerId)
                                    snackbarHostState.showSnackbar("Identity recovered cleanly!")
                                    onRestoredRestartHome()
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Recovery failed: ${e.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Recover Identity Keypair")
                    }
                }
            }
        }
    }
}
