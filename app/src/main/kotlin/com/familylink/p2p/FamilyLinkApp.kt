package com.familylink.p2p

import android.app.Application
import android.content.Intent
import android.os.Build
import com.familylink.core.data.db.FamilyLinkDatabase
import com.familylink.core.data.repository.FamilyLinkRepository
import com.familylink.core.p2p.P2pCoordinator
import com.familylink.core.p2p.transport.DecentralizedRelayTransport
import com.familylink.core.p2p.transport.LanNsdTransport
import com.familylink.core.p2p.transport.WifiDirectTransport
import com.familylink.core.webrtc.MeshCallController
import com.familylink.p2p.service.CallNotificationManager
import com.familylink.p2p.service.P2pDaemonService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class FamilyLinkApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: FamilyLinkDatabase
        private set
    lateinit var repository: FamilyLinkRepository
        private set
    lateinit var callNotificationManager: CallNotificationManager
        private set

    private val _coordinator = MutableStateFlow<P2pCoordinator?>(null)
    val coordinator: StateFlow<P2pCoordinator?> = _coordinator.asStateFlow()

    private val _callController = MutableStateFlow<MeshCallController?>(null)
    val callController: StateFlow<MeshCallController?> = _callController.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        database = FamilyLinkDatabase.createEncrypted(this)
        repository = FamilyLinkRepository(database)
        callNotificationManager = CallNotificationManager(this)

        appScope.launch {
            val identity = repository.getIdentity()
            if (identity != null) {
                ensureP2pStarted(identity.peerId)
            }
        }
    }

    @Synchronized
    fun ensureP2pStarted(localPeerId: String): P2pCoordinator {
        val existing = _coordinator.value
        if (existing != null) return existing

        val lan = LanNsdTransport(this, localPeerId)
        val wifiDirect = WifiDirectTransport(this)
        val relay = DecentralizedRelayTransport(
            localPeerId = localPeerId,
            relayUrlsProvider = { repository.getEnabledRelayUrls() },
            groupIdsProvider = { repository.getGroups().map { it.groupId } }
        )
        val coord = P2pCoordinator(
            repository = repository,
            lanTransport = lan,
            wifiDirectTransport = wifiDirect,
            relayTransport = relay,
            mediaDir = File(filesDir, "media")
        )
        _coordinator.value = coord

        val webrtcController = MeshCallController(
            context = this,
            repository = repository,
            coordinator = coord,
            onIncomingCallNotification = { state ->
                callNotificationManager.showIncomingCall(state)
            },
            onDismissIncomingNotification = {
                callNotificationManager.dismissIncomingCall()
            }
        )
        _callController.value = webrtcController

        appScope.launch {
            coord.start()
        }
        startDaemonService()
        return coord
    }

    fun startDaemonService() {
        updateDaemonMode(P2pDaemonService.ACTION_IDLE)
    }

    fun updateDaemonMode(action: String) {
        val intent = Intent(this, P2pDaemonService::class.java).apply {
            this.action = action
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
        }
    }
}
