package com.familylink.p2p.ui.call

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familylink.core.webrtc.CallSessionStatus
import com.familylink.p2p.FamilyLinkApp
import com.familylink.p2p.MainActivity
import com.familylink.p2p.ui.theme.FamilyLinkTheme

class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val app = application as FamilyLinkApp
        setContent {
            FamilyLinkTheme(darkTheme = true) {
                val controller by app.callController.collectAsState()
                val callState by (controller?.callState ?: remember {
                    kotlinx.coroutines.flow.MutableStateFlow(com.familylink.core.webrtc.ActiveCallState())
                }).collectAsState()

                var pendingAcceptVideo by remember { mutableStateOf<Boolean?>(null) }

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    val video = pendingAcceptVideo ?: return@rememberLauncherForActivityResult
                    pendingAcceptVideo = null
                    controller?.acceptIncomingCall(enableVideo = video)
                    startActivity(
                        Intent(this@IncomingCallActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                    )
                    finish()
                }

                LaunchedEffect(callState.status) {
                    if (callState.status == CallSessionStatus.IDLE || callState.status == CallSessionStatus.ENDED) {
                        finish()
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1E293B),
                            modifier = Modifier.size(110.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color(0xFF2DD4BF),
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = callState.title.ifBlank { "Family Call" },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (callState.isVideoCall) "Incoming Encrypted P2P Video Call"
                            else "Incoming Encrypted P2P Voice Call",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 32.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledIconButton(
                                onClick = {
                                    controller?.declineIncomingCall()
                                    finish()
                                },
                                modifier = Modifier.size(64.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Decline", color = Color.White)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledIconButton(
                                onClick = {
                                    pendingAcceptVideo = false
                                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                },
                                modifier = Modifier.size(64.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF16A34A))
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Accept Voice", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Voice", color = Color.White)
                        }

                        if (callState.isVideoCall) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FilledIconButton(
                                    onClick = {
                                        pendingAcceptVideo = true
                                        permLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.RECORD_AUDIO,
                                                Manifest.permission.CAMERA
                                            )
                                        )
                                    },
                                    modifier = Modifier.size(64.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF0F766E))
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = "Accept Video", tint = Color.White)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Video", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
