package app.pillion.android

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.FileProvider
import app.pillion.android.sdl.SdlSessionState
import app.pillion.core.AppInfo
import app.pillion.core.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Field diagnostics for remote testers without adb. Keeps a rotating on-device copy of this app's
 * logcat (both the app and the SDL router process share our UID, so both are captured) and logs every
 * USB, Wi-Fi, Wi-Fi Direct and Bluetooth link change — so a run of the official app with Pillion in the
 * background shows which link the bike really uses — then packs a snapshot of the phone/USB/SDL state
 * plus those logs into a zip the user can share from Settings.
 */
object DiagnosticLogs {

    private const val DIR = "diag"
    private const val LOG_FILE = "logcat.txt"
    private const val ROTATE_KB = 1024
    private const val ROTATE_COUNT = 4

    // Hidden-API sticky broadcast: tells us whether the phone ever switched into USB accessory (AOA) mode.
    private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

    private var logcat: Process? = null

    // Last compact description per network, so the summary shows what was up and repeats aren't logged.
    private val networks = ConcurrentHashMap<Network, String>()
    private val seenBssids = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastP2p = "(none)"

    /** Start the background capture. Call once from the main process only. */
    fun start(app: Application) {
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        runCatching {
            logcat = ProcessBuilder(
                "logcat", "-v", "threadtime",
                "-f", File(dir, LOG_FILE).absolutePath,
                "-r", ROTATE_KB.toString(), "-n", ROTATE_COUNT.toString(),
            ).redirectErrorStream(true).start()
        }.onFailure { Logger.e("diag: failed to start logcat capture", it) }
        watchUsb(app)
        watchRadios(app)
        watchNetworks(app)
        Logger.d("diag: capture started — ${deviceLine()} app=${AppInfo.VERSION}")
    }

    private fun watchUsb(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Logger.d("diag: ${intent.action} extras=${extrasOf(intent)}")
                Logger.d("diag: accessories now: ${accessories(context)}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_STATE)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }.onFailure { Logger.e("diag: failed to watch USB state", it) }
    }

    /** Wi-Fi Direct, Wi-Fi association, scan results and Bluetooth ACL — the other ways a dash can link up. */
    private fun watchRadios(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> logNewScanResults(context)
                    BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val name = runCatching { device?.name }.getOrNull()
                        Logger.d("diag: ${intent.action} device=$name ${device?.address}")
                    }
                    else -> {
                        val extras = extrasOf(intent)
                        if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) lastP2p = extras
                        Logger.d("diag: ${intent.action} extras=$extras")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }.onFailure { Logger.e("diag: failed to watch radios", it) }
    }

    /** Every network the phone brings up or drops, including local-only ones without internet. */
    private fun watchNetworks(app: Application) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_INCLUDE_LOCATION_INFO else 0,
        ) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val line = describeCaps(caps)
                if (networks.put(network, line) != line) Logger.d("diag: net $network $line")
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                Logger.d(
                    "diag: net $network iface=${lp.interfaceName} addrs=${lp.linkAddresses} " +
                        "routes=${lp.routes.map { "${it.destination}->${it.gateway}" }} dns=${lp.dnsServers}",
                )
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                Logger.d("diag: net $network lost")
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Logger.e("diag: failed to watch networks", it) }
    }

    private fun describeCaps(caps: NetworkCapabilities): String {
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "usb"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val wifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.transportInfo as? WifiInfo else null
        val ssid = wifi?.let { " ssid=${it.ssid} bssid=${it.bssid} freq=${it.frequency}MHz" } ?: ""
        return "$kind internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}$ssid"
    }

    /** Log each access point the first time it shows up — the bike's hotspot or Wi-Fi Direct group among them. */
    private fun logNewScanResults(context: Context) {
        val results = runCatching { context.getSystemService(WifiManager::class.java).scanResults }.getOrNull() ?: return
        results.filter { seenBssids.add(it.BSSID) }.forEach {
            @Suppress("DEPRECATION")
            Logger.d("diag: wifi seen ssid='${it.SSID}' bssid=${it.BSSID} freq=${it.frequency} level=${it.level} caps=${it.capabilities}")
        }
    }

    /** Build the zip and open the system share sheet (WhatsApp, email, Drive…). */
    fun share(context: Context) {
        val zip = runCatching { buildZip(context) }.getOrElse {
            Logger.e("diag: failed to build log zip", it)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagfiles", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Pillion diagnostic logs")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "Share Pillion logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun buildZip(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(context.cacheDir, "diag-share").apply {
            deleteRecursively()
            mkdirs()
        }
        val zip = File(outDir, "pillion-logs-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("summary.txt"))
            out.write(summary(context).toByteArray())
            out.closeEntry()

            // Oldest rotation first (logcat.txt.4 … logcat.txt.1, then the live logcat.txt).
            val dir = File(context.filesDir, DIR)
            val parts = (ROTATE_COUNT downTo 1).map { File(dir, "$LOG_FILE.$it") } + File(dir, LOG_FILE)
            out.putNextEntry(ZipEntry("logcat.txt"))
            parts.filter { it.exists() }.forEach { part -> part.inputStream().use { it.copyTo(out) } }
            out.closeEntry()

            // Whatever is still in the system ring buffer (covers the case the capture wasn't running).
            out.putNextEntry(ZipEntry("logcat-buffer.txt"))
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
                p.inputStream.use { it.copyTo(out) }
            }
            out.closeEntry()
        }
        return zip
    }

    private fun summary(context: Context): String = buildString {
        val pm = context.packageManager
        appendLine("Pillion diagnostics — ${Date()}")
        val installed = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("app: ${AppInfo.VERSION} (installed $installed)")
        appendLine("device: ${deviceLine()}")
        appendLine("selected bike: ${AndroidSettingsStore(context).selectedBikeId() ?: "(none)"}")
        appendLine("sdl state: ${SdlSessionState.state.value}")
        appendLine("wifi link: ${AndroidWifiLink.describe(context)}")
        appendLine()

        appendLine("== Wi-Fi ==")
        val location = runCatching {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled
        }.getOrNull()
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, "android.permission.NEARBY_WIFI_DEVICES")
            .joinToString { "${it.substringAfterLast('.')}=${context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED}" }
        appendLine("location on=$location $granted")
        appendLine("networks up: ${networks.values.ifEmpty { listOf("none") }.joinToString(" | ")}")
        appendLine("wifi direct (last): $lastP2p")
        appendLine("access points seen: ${seenBssids.size}")
        appendLine()

        appendLine("== USB ==")
        appendLine("usb state (last sticky): ${lastUsbState(context)}")
        appendLine("accessories: ${accessories(context)}")
        val usb = context.getSystemService(UsbManager::class.java)
        appendLine("host devices: ${runCatching { usb?.deviceList?.values?.map { "${it.manufacturerName}/${it.productName} vid=${it.vendorId} pid=${it.productId}" } }.getOrNull()}")
        appendLine()

        appendLine("== SDL routers installed (competing apps grab the USB link) ==")
        runCatching {
            pm.queryIntentServices(Intent("com.smartdevicelink.router.service"), PackageManager.GET_META_DATA)
                .forEach { appendLine("- ${it.serviceInfo.packageName}/${it.serviceInfo.name}") }
        }.onFailure { appendLine("query failed: $it") }
        appendLine()

        appendLine("== Related apps installed ==")
        runCatching {
            pm.getInstalledPackages(0)
                .map { it.packageName }
                .filter { name -> listOf("garmin", "motorize", "yamaha", "smartdevicelink").any { name.contains(it, true) } }
                .forEach { appendLine("- $it") }
        }.onFailure { appendLine("query failed: $it") }
        appendLine()

        appendLine("== Bluetooth bonded devices ==")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            appendLine("(BLUETOOTH_CONNECT not granted)")
        } else {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                    ?.forEach { appendLine("- ${it.name} ${it.address}") }
            }.onFailure { appendLine("query failed: $it") }
        }
        appendLine()

        appendLine("== Network ==")
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.forEach { n ->
                val caps = cm.getNetworkCapabilities(n)
                val kind = when {
                    caps == null -> "?"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "usb"
                    else -> "other"
                }
                appendLine("- $kind internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")
            }
        }.onFailure { appendLine("query failed: $it") }
    }

    private fun lastUsbState(context: Context): String {
        val sticky = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, IntentFilter(ACTION_USB_STATE), Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
            }
        }.getOrNull()
        return sticky?.let { extrasOf(it) } ?: "(unavailable)"
    }

    private fun accessories(context: Context): String = runCatching {
        val list = context.getSystemService(UsbManager::class.java)?.accessoryList
        if (list.isNullOrEmpty()) "none"
        else list.joinToString { "manufacturer='${it.manufacturer}' model='${it.model}' version='${it.version}' description='${it.description}' uri='${it.uri}'" }
    }.getOrElse { "query failed: $it" }

    private fun extrasOf(intent: Intent): String {
        val extras = intent.extras ?: return "{}"
        return extras.keySet().sorted().joinToString(prefix = "{", postfix = "}") { "$it=${extras.get(it)}" }
    }

    private fun deviceLine() =
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
