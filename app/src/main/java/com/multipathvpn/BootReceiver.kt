package com.multipathvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * Auto-starts the MultiPath VPN on device boot.
 * Users must enable this in the app settings.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check VPN permission
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent == null) {
                // Permission already granted — auto-start VPN
                val serviceIntent = Intent(context, MultiPathVpnService::class.java).apply {
                    action = MultiPathVpnService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            // If permission not granted, user needs to open app and grant it
        }
    }
}
