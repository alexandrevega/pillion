package app.pillion.core.headunit

/**
 * Identifies a transport kind. OPEN by design — a tiny wrapper, not an enum — so a plugin can mint a
 * new kind (e.g. `LinkKind("ble-l2cap")`) without editing this file (OCP). (A data class rather than a
 * `@JvmInline value class` because that annotation isn't available on Kotlin/Native.)
 */
data class LinkKind(val id: String) {
    companion object {
        val BluetoothRfcomm = LinkKind("bt-rfcomm") // NaviLite over Bluetooth Classic SPP (Android)
        val MfiAccessory = LinkKind("mfi")          // iOS External Accessory session (NaviLite)
        val UsbAoa = LinkKind("usb-aoa")            // Android Open Accessory (SDL)
        val UsbIap2 = LinkKind("usb-iap2")          // iOS iAP2 / EAP (SDL)
        val TcpDebug = LinkKind("tcp")              // dev/emulator fallback
    }
}
