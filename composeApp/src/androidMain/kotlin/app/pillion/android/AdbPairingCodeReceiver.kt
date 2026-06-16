package app.pillion.android

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the Wireless-debugging pairing code typed into Pillion's setup notification. */
class AdbPairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbPairingCoordinator.ACTION_PAIR_CODE) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbPairingCoordinator.KEY_PAIRING_CODE)
            ?.toString()
            ?: intent.getStringExtra(AdbPairingCoordinator.KEY_PAIRING_CODE)
            .orEmpty()
        AdbPairingCoordinator.pairWithDiscoveredEndpoint(context, code)
    }
}
