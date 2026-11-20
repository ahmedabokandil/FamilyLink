package com.familylink.p2p

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.familylink.core.webrtc.ActiveCallState
import com.familylink.core.webrtc.CallSessionStatus
import com.familylink.p2p.ui.call.ActiveCallScreen
import com.familylink.p2p.ui.chat.ChatScreen
import com.familylink.p2p.ui.home.ConversationListScreen
import com.familylink.p2p.ui.onboarding.OnboardingScreen
import com.familylink.p2p.ui.qr.QrPairingScreen
import com.familylink.p2p.ui.settings.BackupRestoreScreen
import com.familylink.p2p.ui.settings.NetworkSettingsScreen
import com.familylink.p2p.ui.theme.FamilyLinkTheme

class MainActivity : ComponentActivity() {

    private var pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractDeepLink(intent)
        val app = application as FamilyLinkApp

        setContent {
            FamilyLinkTheme {
                FamilyLinkNavRoot(
                    app = app,
                    deepLinkUri = pendingDeepLink.value,
                    onDeepLinkConsumed = { pendingDeepLink.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractDeepLink(intent)
    }

    private fun extractDeepLink(intent: Intent?) {
        val data = intent?.dataString
        if (!data.isNullOrBlank() && data.startsWith("famlink://")) {
            pendingDeepLink.value = data
        }
    }
}

@Composable
private fun FamilyLinkNavRoot(
    app: FamilyLinkApp,
    deepLinkUri: String?,
    onDeepLinkConsumed: () -> Unit
) {
    var initialized by remember { mutableStateOf(false) }
    val identity by app.repository.observeIdentity().collectAsState(initial = null)
    val callController by app.callController.collectAsState()
    val callState by (callController?.callState ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(ActiveCallState())
    }).collectAsState()

    var pendingCallRequest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val navController = rememberNavController()

    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val req = pendingCallRequest ?: return@rememberLauncherForActivityResult
        pendingCallRequest = null
        callController?.startCall(req.first, req.second)
        navController.navigate("call") {
            launchSingleTop = true
        }
    }

    LaunchedEffect(Unit) {
        app.repository.getIdentity()
        initialized = true
    }

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (identity == null) "onboarding" else "home"

    LaunchedEffect(deepLinkUri, identity) {
        if (deepLinkUri != null && identity != null) {
            val encoded = Uri.encode(deepLinkUri)
            onDeepLinkConsumed()
            navController.navigate("qr?uri=$encoded")
        }
    }

    LaunchedEffect(callState.status) {
        if (callState.status == CallSessionStatus.ACTIVE || callState.status == CallSessionStatus.OUTGOING_RINGING) {
            if (navController.currentDestination?.route != "call") {
                navController.navigate("call") { launchSingleTop = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("onboarding") {
            OnboardingScreen(
                app = app,
                onCompleted = {
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }
        composable("home") {
            ConversationListScreen(
                app = app,
                onOpenChat = { convId ->
                    navController.navigate("chat/${Uri.encode(convId)}")
                },
                onOpenQrPairing = {
                    navController.navigate("qr")
                },
                onOpenSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable(
            route = "qr?uri={uri}",
            arguments = listOf(navArgument("uri") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val uriArg = backStackEntry.arguments?.getString("uri")
            QrPairingScreen(
                app = app,
                initialInviteUri = uriArg,
                onBack = { navController.popBackStack() },
                onPairedOpenConversation = { convId ->
                    navController.navigate("chat/${Uri.encode(convId)}") {
                        popUpTo("home")
                    }
                }
            )
        }
        composable(
            route = "chat/{conversationId}",
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val convId = Uri.decode(backStackEntry.arguments?.getString("conversationId").orEmpty())
            ChatScreen(
                app = app,
                conversationId = convId,
                onBack = { navController.popBackStack() },
                onStartCall = { targetConvId, isVideo ->
                    pendingCallRequest = targetConvId to isVideo
                    val perms = if (isVideo) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    }
                    callPermLauncher.launch(perms)
                }
            )
        }
        composable("call") {
            val ctrl = callController
            if (ctrl != null) {
                ActiveCallScreen(
                    app = app,
                    controller = ctrl,
                    onCallEnded = { navController.popBackStack() }
                )
            }
        }
        composable("settings") {
            NetworkSettingsScreen(
                app = app,
                onBack = { navController.popBackStack() },
                onOpenBackup = { navController.navigate("backup") }
            )
        }
        composable("backup") {
            BackupRestoreScreen(
                app = app,
                onBack = { navController.popBackStack() },
                onRestoredRestartHome = {
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
