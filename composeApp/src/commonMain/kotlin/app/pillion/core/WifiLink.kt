package app.pillion.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Experimental: reach an SDL head unit over the bike's Wi-Fi (TCP) instead of the USB cable. Some
 * dashes (e.g. the Tracer 9 GT) let Motorize connect either way. The UI only sees this abstraction;
 * platforms that can't offer it pass `null` and the section is hidden.
 */
interface WifiLink {
    /** One-line scan status / result for Settings. */
    val status: StateFlow<String>

    /** Whether Start should connect over Wi-Fi instead of USB. */
    val enabled: StateFlow<Boolean>

    fun setEnabled(enabled: Boolean)

    /** Probe the current Wi-Fi network for the head unit (gateway ports, mDNS, SSDP) and log it all. */
    fun scan()
}
