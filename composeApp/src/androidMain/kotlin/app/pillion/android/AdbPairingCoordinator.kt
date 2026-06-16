package app.pillion.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import app.pillion.MainActivity
import app.pillion.core.DashStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdbPairingEndpoint(val host: String, val port: Int)

data class AdbPairingState(
    val stage: DashStage = DashStage.Idle,
    val message: String? = null,
    val endpoint: AdbPairingEndpoint? = null,
)

private data class PairingSubmission(
    val code: String,
    val endpoint: AdbPairingEndpoint?,
)

/**
 * Coordinates the no-PC Wireless-debugging bootstrap:
 *
 * 1. discover the ephemeral `_adb-tls-pairing._tcp` service while the system pairing dialog is open;
 * 2. keep a notification visible with a RemoteInput action so the user can type the 6-digit code
 *    without leaving Settings; and
 * 3. pair/connect with the persisted Pillion ADB key.
 */
object AdbPairingCoordinator {
    const val ACTION_PAIR_CODE = "app.pillion.action.PAIR_CODE"
    const val KEY_PAIRING_CODE = "app.pillion.extra.PAIRING_CODE"

    private const val CHANNEL_ID = "pillion_adb_pairing"
    private const val NOTIFICATION_ID = 42
    private const val SERVICE_TYPE = "_adb-tls-pairing._tcp."
    private const val SERVICE_TYPE_NORMALIZED = "_adb-tls-pairing._tcp"
    private const val LOCALHOST = "127.0.0.1"
    private const val TAG = "Pillion"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AdbPairingState())
    val state: StateFlow<AdbPairingState> = _state.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var resolving = false

    fun start(context: Context) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        postPairingNotification(appContext, "Open the pairing-code dialog, then enter its code here.")
        startDiscovery(appContext)
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        stopDiscovery(appContext)
        appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun pairWithDiscoveredEndpoint(context: Context, input: String) {
        scope.launch { pairWithDiscoveredEndpointBlocking(context.applicationContext, input.trim()) }
    }

    fun pairExplicit(context: Context, endpoint: AdbPairingEndpoint, code: String) {
        scope.launch { pair(context.applicationContext, endpoint, code.trim()) }
    }

    fun connect(context: Context) {
        val appContext = context.applicationContext
        _state.value = _state.value.copy(stage = DashStage.Connecting, message = "Connecting to Wireless debugging...")
        scope.launch {
            runCatching { PillionAdb.getInstance(appContext).autoConnectDevice(appContext, timeoutMs = 15_000) }
                .onSuccess { connected ->
                    _state.value = _state.value.copy(
                        stage = if (connected) DashStage.Connected else DashStage.Error,
                        message = if (connected) "Connected as shell." else "Couldn't find the paired ADB service.",
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(
                        stage = DashStage.Error,
                        message = t.message ?: "Connect failed",
                    )
                }
        }
    }

    private suspend fun pairWithDiscoveredEndpointBlocking(context: Context, input: String) {
        val submission = parsePairingSubmission(input)
        if (submission == null) {
            _state.value = _state.value.copy(
                stage = DashStage.Error,
                message = "Enter the 6-digit code, or the pairing port and code.",
            )
            postPairingNotification(context, "Enter code, or port and code, from the pairing dialog.")
            return
        }

        val endpoint = submission.endpoint
            ?: waitForEndpoint()
            ?: run {
                Log.d(TAG, "adb pairing: no cached endpoint, restarting discovery for code submit")
                restartDiscovery(context)
                waitForEndpoint()
            }
        if (endpoint == null) {
            _state.value = _state.value.copy(
                stage = DashStage.Error,
                message = "No pairing port found. Enter the port and code from the dialog.",
            )
            postPairingNotification(context, "Port not found. Reply with: port code")
            return
        }

        pair(context, endpoint, submission.code)
    }

    private suspend fun waitForEndpoint(timeoutMs: Long = 12_000): AdbPairingEndpoint? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            state.value.endpoint?.let { return it }
            delay(150)
        }
        return state.value.endpoint
    }

    private fun pair(context: Context, endpoint: AdbPairingEndpoint, code: String) {
        val hosts = listOf(LOCALHOST, endpoint.host).distinct()
        _state.value = _state.value.copy(
            stage = DashStage.Pairing,
            message = "Pairing on port ${endpoint.port}...",
            endpoint = endpoint,
        )
        postPairingNotification(context, "Pairing on port ${endpoint.port}...")

        var lastFailure: Throwable? = null
        for (host in hosts) {
            runCatching {
                PillionAdb.getInstance(context).pairDevice(host, endpoint.port, code)
            }.onSuccess {
                _state.value = _state.value.copy(stage = DashStage.Connecting, message = "Paired. Connecting...")
                val connected = PillionAdb.getInstance(context).autoConnectDevice(context, timeoutMs = 15_000)
                _state.value = _state.value.copy(
                    stage = if (connected) DashStage.Connected else DashStage.Error,
                    message = if (connected) "Connected as shell." else "Paired, but could not connect.",
                )
                postPairingNotification(
                    context,
                    if (connected) "Connected. Tap to return to Pillion." else "Paired, but connect failed.",
                    ongoing = !connected,
                    showOpenAction = connected,
                )
                return
            }.onFailure { lastFailure = it }
        }

        _state.value = _state.value.copy(
            stage = DashStage.Error,
            message = lastFailure?.message ?: "Pairing failed. Check the code before it expires.",
        )
        postPairingNotification(context, "Pairing failed. Open the dialog and try again.")
    }

    private fun parsePairingSubmission(input: String): PairingSubmission? {
        if (input.isBlank()) return null
        val hostPort = Regex("""(\d{1,3}(?:\.\d{1,3}){3})\s*:\s*(\d{1,5})""").find(input)
        val numbers = Regex("""\d+""").findAll(input).map { it.value }.toList()
        val compactDigits = input.filter { it.isDigit() }
        val code = numbers.firstOrNull { it.length == 6 }
            ?: compactDigits.takeIf { it.length == 6 }
            ?: return null

        val host = hostPort?.groupValues?.getOrNull(1) ?: LOCALHOST
        val port = hostPort?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: numbers.firstNotNullOfOrNull { number ->
                number.toIntOrNull()?.takeIf { number != code && it in 1..65535 }
            }
        return PairingSubmission(code = code, endpoint = port?.let { AdbPairingEndpoint(host, it) })
    }

    private fun startDiscovery(context: Context) {
        if (discoveryListener != null) return

        multicastLock = runCatching {
            context.getSystemService(WifiManager::class.java)
                .createMulticastLock("pillion-adb-pairing")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()

        val nsd = context.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "adb pairing: discovery started for $serviceType")
                _state.value = _state.value.copy(
                    stage = DashStage.Idle,
                    message = "Waiting for the Wireless debugging pairing dialog...",
                )
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "adb pairing: service found ${serviceInfo.serviceName} ${serviceInfo.serviceType}")
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE_NORMALIZED || resolving) return
                resolving = true
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        Log.w(TAG, "adb pairing: resolve failed $errorCode for ${serviceInfo.serviceName}")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        val host = serviceInfo.host?.hostAddress ?: LOCALHOST
                        val endpoint = AdbPairingEndpoint(host, serviceInfo.port)
                        Log.d(TAG, "adb pairing: resolved ${serviceInfo.serviceName} to $host:${serviceInfo.port}")
                        _state.value = _state.value.copy(
                            endpoint = endpoint,
                            message = "Found pairing port ${endpoint.port}. Enter the code from Settings.",
                        )
                        postPairingNotification(context, "Found port ${endpoint.port}. Enter the 6-digit code.")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "adb pairing: service lost ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "adb pairing: discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
                Log.w(TAG, "adb pairing: discovery failed $errorCode for $serviceType")
                _state.value = _state.value.copy(
                    stage = DashStage.Error,
                    message = "ADB service discovery failed ($errorCode). Use split screen as fallback.",
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "adb pairing: stop discovery failed $errorCode for $serviceType")
            }
        }

        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { t ->
                discoveryListener = null
                _state.value = _state.value.copy(
                    stage = DashStage.Error,
                    message = t.message ?: "ADB service discovery failed",
                )
            }
    }

    private fun restartDiscovery(context: Context) {
        stopDiscovery(context)
        startDiscovery(context)
    }

    private fun stopDiscovery(context: Context) {
        val nsd = context.getSystemService(NsdManager::class.java)
        discoveryListener?.let { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        discoveryListener = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        resolving = false
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pillion setup", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun postPairingNotification(
        context: Context,
        text: String,
        ongoing: Boolean = true,
        showOpenAction: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = _state.value.copy(message = "Allow notifications so Pillion can ask for the code in Settings.")
            return
        }

        val replyIntent = Intent(context, AdbPairingCodeReceiver::class.java).setAction(ACTION_PAIR_CODE)
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("Code, or port and code")
            .build()
        val replyAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Enter code",
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val contentIntent = openPillionPendingIntent(context)
        val openAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Open Pillion",
            contentIntent,
        ).build()

        val builder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        })
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Pillion Wireless debugging setup")
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(contentIntent)

        if (showOpenAction) {
            builder.addAction(openAction)
        } else {
            builder.addAction(replyAction)
        }

        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    private fun openPillionPendingIntent(context: Context): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
