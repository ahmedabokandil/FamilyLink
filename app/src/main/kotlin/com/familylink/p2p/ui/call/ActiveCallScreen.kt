package com.familylink.p2p.ui.call

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familylink.core.webrtc.CallSessionStatus
import com.familylink.core.webrtc.MeshCallController
import com.familylink.p2p.FamilyLinkApp
import com.familylink.p2p.service.P2pDaemonService

@Composable
fun ActiveCallScreen(
    app: FamilyLinkApp,
    controller: MeshCallController,
    onCallEnded: () -> Unit
) {
    val context = LocalContext.current
    val callState by controller.callState.collectAsState()
    val localVideo by controller.localVideoTrack.collectAsState()
    val remoteVideos by controller.remoteVideoTracks.collectAsState()
    val eglContext = controller.engine.eglBase.eglBaseContext

    LaunchedEffect(callState.status) {
        if (callState.status == CallSessionStatus.IDLE || callState.status == CallSessionStatus.ENDED) {
            app.updateDaemonMode(P2pDaemonService.ACTION_IDLE)
            onCallEnded()
        } else {
            app.updateDaemonMode(
                if (callState.localScreenSharing) P2pDaemonService.ACTION_MEDIA_PROJECTION
                else P2pDaemonService.ACTION_ACTIVE_CALL
            )
        }
    }

    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            app.updateDaemonMode(P2pDaemonService.ACTION_MEDIA_PROJECTION)
            controller.startScreenShare(data)
        }
    }

    val participants = callState.participants.values.toList()
    val screenSharingPeer = participants.firstOrNull { it.screenSharing }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top call header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, start = 20.dp, end = 20.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = callState.title.ifBlank { "Family Call" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                val subtitle = when (callState.status) {
                    CallSessionStatus.OUTGOING_RINGING -> "Ringing P2P (DTLS-SRTP E2EE)..."
                    CallSessionStatus.ACTIVE -> "${participants.size + 1} in call • Full-Mesh E2EE"
                    else -> "Connecting..."
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }

            // Video / Voice Participant Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                if (screenSharingPeer != null && remoteVideos[screenSharingPeer.peerId] != null) {
                    // Auto-pinned screen share view
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                        ) {
                            WebRtcVideoRenderer(
                                videoTrack = remoteVideos[screenSharingPeer.peerId],
                                eglContext = eglContext,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "${screenSharingPeer.displayName} is sharing screen",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else if (participants.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF1E293B),
                                modifier = Modifier.size(96.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color(0xFF2DD4BF),
                                    modifier = Modifier.padding(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Waiting for family members to answer...", color = Color(0xFFCBD5E1))
                        }
                    }
                } else {
                    val columns = if (participants.size == 1) 1 else 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(participants, key = { it.peerId }) { participant ->
                            val track = remoteVideos[participant.peerId]
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (participants.size == 1) 460.dp else 230.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (track != null && participant.videoEnabled) {
                                    WebRtcVideoRenderer(
                                        videoTrack = track,
                                        eglContext = eglContext,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = Color(0xFF2DD4BF),
                                            modifier = Modifier.size(56.dp)
                                        )
                                        Text(participant.displayName, color = Color.White)
                                    }
                                }
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = buildString {
                                            append(participant.displayName)
                                            if (!participant.audioEnabled) append(" • Muted")
                                        },
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Local PiP video overlay
                if (localVideo != null && (callState.localVideoEnabled || callState.localScreenSharing)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(width = 110.dp, height = 156.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color.Black)
                    ) {
                        WebRtcVideoRenderer(
                            videoTrack = localVideo,
                            eglContext = eglContext,
                            mirror = !callState.localScreenSharing,
                            zOrderMediaOverlay = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Bottom Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = { controller.toggleMute() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callState.localAudioEnabled) Color(0xFF334155) else Color(0xFFEF4444)
                    )
                ) {
                    Icon(
                        imageVector = if (callState.localAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Mute",
                        tint = Color.White
                    )
                }

                FilledIconButton(
                    onClick = { controller.toggleCamera() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callState.localVideoEnabled) Color(0xFF334155) else Color(0xFF475569)
                    )
                ) {
                    Icon(
                        imageVector = if (callState.localVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = "Camera",
                        tint = Color.White
                    )
                }

                FilledIconButton(
                    onClick = { controller.flipCamera() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF334155))
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Flip Camera", tint = Color.White)
                }

                FilledIconButton(
                    onClick = { controller.toggleSpeaker() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callState.speakerEnabled) Color(0xFF0F766E) else Color(0xFF334155)
                    )
                ) {
                    Icon(
                        imageVector = if (callState.speakerEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Speaker",
                        tint = Color.White
                    )
                }

                FilledIconButton(
                    onClick = {
                        if (callState.localScreenSharing) {
                            controller.stopScreenShare()
                            app.updateDaemonMode(P2pDaemonService.ACTION_ACTIVE_CALL)
                        } else {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenShareLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callState.localScreenSharing) Color(0xFF0284C7) else Color(0xFF334155)
                    )
                ) {
                    Icon(
                        imageVector = if (callState.localScreenSharing) Icons.Default.StopScreenShare else Icons.Default.PresentToAll,
                        contentDescription = "Screen Share",
                        tint = Color.White
                    )
                }

                FilledIconButton(
                    onClick = { controller.hangUp() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Hang Up", tint = Color.White)
                }
            }
        }
    }
}
