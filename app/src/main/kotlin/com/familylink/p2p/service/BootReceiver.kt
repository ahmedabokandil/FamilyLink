package com.familylink.p2p.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familylink.p2p.FamilyLinkApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            (context.applicationContext as? FamilyLinkApp)?.startDaemonService()
        }
    }
}
