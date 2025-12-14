package com.wifip2p

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * MainActivity.kt — includes saving the last selected mode (Parent/Child)
 * to SharedPreferences under PREF_IS_PARENT (boolean). On startup the saved
 * choice is respected: if saved true -> Parent UI + startParentFlow(),
 * if false -> Child UI + startChildFlow(). If no saved preference, show choice
 * container as before.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WIFIP2P_COMBINED"
        private const val PREFS_NAME = "wifip2p_prefs"
        private const val PREF_LAST_GO = "last_go"
        private const val PREF_IS_PARENT = "is_parent"
    }

    // ---------- UI ----------
    private lateinit var tvMode: TextView
    private lateinit var choiceContainer: View
    private lateinit var btnParent: Button
    private lateinit var btnChild: Button

    // Parent UI
    private lateinit var parentContainer: View
    private lateinit var tvParentStatus: TextView
    private lateinit var etParentMessage: EditText
    private lateinit var btnSendParent: Button

    // Child UI
    private lateinit var childContainer: View
    private lateinit var tvChildStatus: TextView
    private lateinit var tvChildMessage: TextView

    // Shared log view
    private lateinit var tvLog: TextView
    private val handler = Handler(Looper.getMainLooper())

    // ---------- Wi-Fi P2P ----------
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    // Parent ownership / group state
    @Volatile private var parentIsGroupOwner = false

    // ---------- Parent state ----------
    private val clients = CopyOnWriteArrayList<Socket>()
    private var serverSocket: ServerSocket? = null
    private val SERVER_PORT = 8988

    @Volatile private var keepCreatingGroup = false
    @Volatile private var serverRunning = false

    // ---------- Child state ----------
    @Volatile private var keepTryingToConnect = false
    @Volatile private var clientSocket: Socket? = null
    private var lastGroupOwnerAddress: String? = null
    private val isParentDevice = BuildConfig.isParent
    @Volatile private var discoveryRequested = false

    // Prevent rapid duplicate connect() calls
    @Volatile private var connectingToDeviceAddress: String? = null

    // saved retry backoff settings for socket reconnect
    @Volatile private var savedGoBackoffMs = 1000L
    private val maxSavedGoBackoff = 16000L

    // discovery/connect attempt cooldown timestamps
    @Volatile private var lastDiscoveryAttemptAt = 0L
    @Volatile private var lastConnectAttemptAt = 0L


    // Runtime permissions launcher
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        logD("Permission result: $result")
    }

    // -------------------------
    // Activity lifecycle
    // -------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate()")
        setContentView(R.layout.activity_main)

        // bind UI
        tvMode = findViewById(R.id.tvMode)
        choiceContainer = findViewById(R.id.choiceContainer)
        btnParent = findViewById(R.id.btnParent)
        btnChild = findViewById(R.id.btnChild)

        parentContainer = findViewById(R.id.parentContainer)
        tvParentStatus = findViewById(R.id.tvParentStatus)
        etParentMessage = findViewById(R.id.etParentMessage)
        btnSendParent = findViewById(R.id.btnSendParent)

        childContainer = findViewById(R.id.childContainer)
        tvChildStatus = findViewById(R.id.tvChildStatus)
        tvChildMessage = findViewById(R.id.tvChildMessage)

        tvLog = findViewById(R.id.tvLog)

        // Wifi P2P manager init
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = CombinedBroadcastReceiver()

        ensurePermissions()

        // UI handlers
        btnParent.setOnClickListener {
            logD("Parent button clicked")
            saveModeAndShow(true)
        }

        btnChild.setOnClickListener {
            logD("Child button clicked")
            saveModeAndShow(false)
        }

        btnSendParent.setOnClickListener {
            val text = etParentMessage.text.toString()
            logD("Send pressed (Parent). message='$text'")
            if (text.isNotBlank()) broadcastFromParent(text)
        }

        // Auto discovery helper (runs continuously but only calls discover when in child UI)
        thread(name = "auto-discovery-helper") {
            while (!isFinishing) {
                if (childContainer.visibility == View.VISIBLE && !discoveryRequested) {
                    try {
                        logD("Auto discovery helper: calling discoverPeers()")
                        discoverPeers()
                    } catch (e: Exception) {
                        logW("Auto discovery thrown: ${e.message}")
                    }
                }
                Thread.sleep(10000)
            }
        }

        // Restore saved mode preference and react accordingly:
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(PREF_IS_PARENT)) {
            val isParent = prefs.getBoolean(PREF_IS_PARENT, false)
            logD("Restored PREF_IS_PARENT = $isParent. Auto-show mode.")
            if (isParent) {
                showParentUI()
                // start parent flow after a small delay to let UI settle
                handler.postDelayed({ startParentFlow() }, 250L)
            } else {
                showChildUI()
                handler.postDelayed({ startChildFlow() }, 250L)
            }
        } else {
            // no saved preference — show choice container (original behavior)
            logD("No saved PREF_IS_PARENT — showing choice UI")
            choiceContainer.visibility = View.VISIBLE
            parentContainer.visibility = View.GONE
            childContainer.visibility = View.GONE
            tvMode.text = "Mode: Not selected"
        }
    }

    override fun onResume() {
        super.onResume()
        logD("onResume() - registering receiver")
        try { registerReceiver(receiver, intentFilter) } catch (e: Exception) { logW("registerReceiver failed: ${e.message}") }
    }

    override fun onPause() {
        super.onPause()
        logD("onPause() - unregistering receiver")
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        logD("onDestroy() - cleanup")
        // parent cleanup
        keepCreatingGroup = false
        serverRunning = false
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { logD("removeGroup() success onDestroy") }
                override fun onFailure(reason: Int) { logW("removeGroup() failed onDestroy: $reason") }
            })
        } catch (e: Exception) { logW("removeGroup threw: ${e.message}") }
        try { serverSocket?.close() } catch (_: Exception) {}
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        // child cleanup
        keepTryingToConnect = false
        try {
            manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { logD("cancelConnect success onDestroy") }
                override fun onFailure(reason: Int) { logW("cancelConnect failed onDestroy: $reason") }
            })
        } catch (e: Exception) { logW("cancelConnect threw: ${e.message}") }
        try { clientSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    // Save the chosen mode then show UI and start flows
    private fun saveModeAndShow(isParent: Boolean) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_IS_PARENT, isParent).apply()
            logD("Saved PREF_IS_PARENT = $isParent")
        } catch (e: Exception) {
            logW("Failed to save PREF_IS_PARENT: ${e.message}")
        }

        if (isParent) {
            showParentUI()
            startParentFlow()
        } else {
            showChildUI()
            startChildFlow()
        }
    }

    // -------------------------
    // UI helpers
    // -------------------------
    private fun showParentUI() {
        logD("Switching UI to Parent mode")
        choiceContainer.visibility = View.GONE
        parentContainer.visibility = View.VISIBLE
        childContainer.visibility = View.GONE
        tvMode.text = "Mode: Parent (Group Owner)"
        logOnUi("Mode set to Parent")
    }

    private fun showChildUI() {
        logD("Switching UI to Child mode")
        choiceContainer.visibility = View.GONE
        parentContainer.visibility = View.GONE
        childContainer.visibility = View.VISIBLE
        tvMode.text = "Mode: Child (Client)"
        logOnUi("Mode set to Child")
    }

    private fun ensurePermissions() {
        logD("ensurePermissions()")
        val needed = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (needed.isNotEmpty()) {
            logD("Requesting permissions: $needed")
            requestPermissions.launch(needed.toTypedArray())
        } else {
            logD("Required runtime permissions already granted")
        }
    }

    // -------------------------
    // Parent (Group Owner) flow
    // -------------------------
    private fun startParentFlow() {
        logD("startParentFlow() - starting robust group creation + server management")
        keepCreatingGroup = true

        thread(name = "parent-createGroup-loop") {
            var attempt = 0
            var backoffMs = 1000L
            val maxBackoff = 16000L

            while (!isFinishing && keepCreatingGroup) {
                if (parentIsGroupOwner) {
                    logD("Parent: currently group owner — pause createGroup loop until group lost")
                    while (!isFinishing && parentIsGroupOwner) {
                        Thread.sleep(1000)
                    }
                    continue
                }

                attempt++
                logD("Parent: createGroup attempt #$attempt")

                if (!serverRunning) {
                    try {
                        logD("Parent: best-effort removeGroup() to clear leftovers before create")
                        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { logD("Parent.removeGroup() success (no leftover)") }
                            override fun onFailure(reason: Int) { logW("Parent.removeGroup() failed: $reason (continuing)") }
                        })
                        Thread.sleep(600)
                    } catch (e: Exception) {
                        logW("Parent: removeGroup() threw: ${e.message}")
                    }
                } else {
                    logD("Parent: serverRunning==true, skipping removeGroup()")
                }

                val latch = Object()
                var callbackReceived = false
                var lastFailureReason = -1

                try {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.NEARBY_WIFI_DEVICES
                        ) != PackageManager.PERMISSION_GRANTED)
                    ) {
                        // permission missing — nothing to do here
                    }
                    manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            logD("Parent.createGroup() onSuccess")
                            parentIsGroupOwner = true
                            callbackReceived = true
                            lastFailureReason = -1
                            logOnUi("Parent: createGroup success")
                            startServerIfNeeded()
                            synchronized(latch) { latch.notify() }
                        }

                        override fun onFailure(reason: Int) {
                            parentIsGroupOwner = false
                            callbackReceived = true
                            lastFailureReason = reason
                            logW("Parent.createGroup() onFailure reason=$reason")
                            logOnUi("Parent: createGroup failed: $reason")
                            synchronized(latch) { latch.notify() }
                        }
                    })
                } catch (e: Exception) {
                    logE("Parent: exception calling createGroup(): ${e.message}")
                }

                synchronized(latch) {
                    try { latch.wait(4000) } catch (_: InterruptedException) {}
                }
                if (!callbackReceived) {
                    logW("Parent: createGroup callback not received - treating as BUSY")
                    lastFailureReason = WifiP2pManager.BUSY
                }

                if (lastFailureReason == WifiP2pManager.BUSY) {
                    logW("Parent: framework BUSY. Backing off ${backoffMs}ms")
                    logOnUi("Parent: framework busy — retrying in ${backoffMs/1000}s")
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoff)
                    continue
                }

                if (lastFailureReason == WifiP2pManager.P2P_UNSUPPORTED) {
                    logE("Parent: P2P_UNSUPPORTED — aborting parent creation loop")
                    logOnUi("Parent: Wi-Fi Direct unsupported on this device.")
                    keepCreatingGroup = false
                    break
                }

                if (lastFailureReason == WifiP2pManager.ERROR || lastFailureReason != -1) {
                    logW("Parent: createGroup failed with reason=$lastFailureReason; retry after short delay")
                    Thread.sleep(2000)
                    continue
                }

                var waitedForInfo = 0
                while (!isFinishing && !parentIsGroupOwner && waitedForInfo < 5000) {
                    Thread.sleep(250)
                    waitedForInfo += 250
                }
            } // end loop

            logD("Parent: createGroup loop exiting")
        }
    }

    private fun startServerIfNeeded() {
        logD("startServerIfNeeded() - serverRunning=$serverRunning")
        if (serverRunning) {
            logD("Server already running, skipping start")
            return
        }
        serverRunning = true
        thread(name = "parent-server") {
            try {
                logD("Opening ServerSocket on port $SERVER_PORT")
                serverSocket = ServerSocket(SERVER_PORT)
                logOnUi("Parent: Server listening on port $SERVER_PORT")
                while (!serverSocket!!.isClosed) {
                    logD("Parent: waiting for accept()")
                    try {
                        val client = serverSocket!!.accept()
                        logD("Parent: accepted from ${client.inetAddress.hostAddress}")
                        clients.add(client)
                        logOnUi("Parent: Client connected: ${client.inetAddress.hostAddress}")

                        // per-client reader
                        thread(name = "parent-client-reader-${client.inetAddress.hostAddress}") {
                            logD("Parent: starting reader for ${client.inetAddress.hostAddress}")
                            try {
                                val buffered = InputStreamReader(client.getInputStream()).buffered()
                                val reader = java.io.BufferedReader(buffered)
                                var line = ""
                                while (!client.isClosed && reader.readLine().also { line = it } != null) {
                                    logD("Parent: received from ${client.inetAddress.hostAddress}: $line")
                                    logOnUi("Parent got from ${client.inetAddress.hostAddress}: $line")
                                }
                                logD("Parent: reader loop ended for ${client.inetAddress.hostAddress}")
                            } catch (e: Exception) {
                                logW("Parent: reader error for ${client.inetAddress.hostAddress}: ${e.message}")
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                                clients.remove(client)
                                logOnUi("Parent: Client disconnected: ${client.inetAddress.hostAddress}")
                                logD("Parent: cleaned up client ${client.inetAddress.hostAddress}")
                            }
                        }
                    } catch (e: Exception) {
                        logW("Parent: accept loop error: ${e.message}")
                        logOnUi("Parent: Server accept error: ${e.message}. continuing.")
                        Thread.sleep(1000)
                    }
                }
            } catch (e: Exception) {
                logE("Parent: Server socket fatal error: ${e.message}")
                logOnUi("Parent: Server socket error: ${e.message}")
            } finally {
                logD("Parent server thread cleaning up")
                try { serverSocket?.close() } catch (_: Exception) {}
                serverRunning = false
            }
        }
    }

    @Synchronized
    private fun broadcastFromParent(message: String) {
        logD("broadcastFromParent() message='$message' clients=${clients.size}")
        thread(name = "parent-broadcast") {
            val toRemove = mutableListOf<Socket>()
            clients.forEach { socket ->
                try {
                    if (socket.isClosed) {
                        logW("Parent: socket closed, marking for removal: ${socket.inetAddress.hostAddress}")
                        toRemove.add(socket)
                        return@forEach
                    }
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    writer.write(message)
                    writer.newLine()
                    writer.flush()
                    logD("Parent: sent to ${socket.inetAddress.hostAddress}")
                } catch (e: Exception) {
                    logW("Parent: failed to send to ${socket.inetAddress.hostAddress}: ${e.message}")
                    toRemove.add(socket)
                }
            }
            toRemove.forEach {
                try { it.close() } catch (_: Exception) {}
                clients.remove(it)
                logD("Parent: removed socket ${it.inetAddress.hostAddress}")
            }
            logOnUi("Parent: Broadcast complete. removed ${toRemove.size} sockets.")
        }
    }

    // -------------------------
    // Child (Client) flow
    // -------------------------
    private fun startChildFlow() {
        logD("startChildFlow() - starting discovery + connect loops")
        discoveryRequested = false
        keepTryingToConnect = true
        savedGoBackoffMs = 1000L

        // Try saved GO IP reconnect first (fast path) — if we have one from prefs
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(PREF_LAST_GO, null)
            if (!saved.isNullOrBlank()) {
                logD("startChildFlow: found saved last_go=$saved — attempting direct client connect first")
                startClientRetryLoop(saved)
                handler.postDelayed({ discoverPeers() }, 1500L)
                return
            }
        } catch (e: Exception) {
            logW("startChildFlow: error reading saved last_go: ${e.message}")
        }

        discoverPeers()
    }

    // Diagnostic & guarded discoverPeers
    private fun discoverPeers() {
        val now = System.currentTimeMillis()
        if (now - lastDiscoveryAttemptAt < 800) {
            logD("discoverPeers(): recent discovery attempt; skipping (cooldown)")
            return
        }
        lastDiscoveryAttemptAt = now

        logD("discoverPeers() called - diagnostics")

        if (discoveryRequested) {
            logD("discoverPeers(): discovery already in progress — skipping")
            return
        }

        try {
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { logD("stopPeerDiscovery() succeeded (cleared prior request)") }
                override fun onFailure(reason: Int) { logW("stopPeerDiscovery() failed: $reason (continuing)") }
            })
        } catch (e: Exception) {
            logW("stopPeerDiscovery threw: ${e.message}")
        }

        discoveryRequested = true
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED)
            ) {
                discoveryRequested = false
                logW("discoverPeers: missing permissions - aborting")
                return
            }
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    logD("discoverPeers onSuccess")
                    logOnUi("Child: Discovery started")
                }
                override fun onFailure(reason: Int) {
                    discoveryRequested = false
                    logW("discoverPeers onFailure reason=$reason")
                    when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> logOnUi("Child: Discovery failed: P2P_UNSUPPORTED")
                        WifiP2pManager.BUSY -> {
                            logOnUi("Child: Discovery busy — backing off before retry")
                            handler.postDelayed({ discoverPeers() }, 3000L)
                        }
                        else -> logOnUi("Child: Discovery failed: ERROR($reason)")
                    }
                }
            })
        } catch (e: Exception) {
            discoveryRequested = false
            logE("discoverPeers threw: ${e.message}")
            logOnUi("Child: Discovery exception: ${e.message}")
        }
    }

    private fun startClientRetryLoop(host: String) {
        logD("startClientRetryLoop(host=$host)")
        if (clientSocket != null && clientSocket!!.isConnected) {
            logD("Already connected to ${clientSocket!!.inetAddress.hostAddress}")
            return
        }
        keepTryingToConnect = true

        thread(name = "child-connector-$host") {
            var attempt = 0
            var backoff = 1000L
            while (!isFinishing && keepTryingToConnect) {
                attempt++
                val now = System.currentTimeMillis()
                if (now - lastConnectAttemptAt < 700) {
                    Thread.sleep(300)
                }
                lastConnectAttemptAt = System.currentTimeMillis()

                logD("Child: connect attempt #$attempt to $host:$SERVER_PORT (backoff=${backoff}ms)")
                try {
                    val sock = Socket()
                    sock.soTimeout = 10000
                    sock.connect(InetSocketAddress(host, SERVER_PORT), 5000)
                    clientSocket = sock
                    logD("Child: connected to $host:$SERVER_PORT")
                    logOnUi("Child: Connected to GO $host:$SERVER_PORT")
                    savedGoBackoffMs = 1000L

                    val reader = java.io.BufferedReader(InputStreamReader(sock.getInputStream()))
                    var line = ""
                    while (!isFinishing && sock.isConnected && reader.readLine().also { line = it } != null) {
                        logD("Child: Received: $line")
                        handler.post { tvChildMessage.text = line }
                        logOnUi("Child: Received: $line")
                    }
                    logD("Child: read loop ended; closing socket")
                    try { sock.close() } catch (_: Exception) {}
                    clientSocket = null
                    logOnUi("Child: Disconnected; will retry")
                } catch (e: Exception) {
                    logW("Child: connection attempt failed: ${e.message}")
                    logOnUi("Child: Connect failed: ${e.message}. retrying in ${backoff}ms")
                    try { clientSocket?.close() } catch (_: Exception) {}
                    clientSocket = null
                    Thread.sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(8000L)
                    handler.postDelayed({ discoverPeers() }, 1200L)
                }
            }
            logD("Child connector thread exiting")
        }
    }

    // Request peers and log them (called on PEERS_CHANGED)
    private fun requestPeersAndLog() {
        try {
            logD("requestPeersAndLog() calling manager.requestPeers()")
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED)
            ) {
                logW("requestPeersAndLog: missing permissions")
                return
            }
            manager.requestPeers(channel, peerListListener)
        } catch (e: Exception) {
            logW("requestPeersAndLog exception: ${e.message}")
        }
    }

    // Connect helper using WifiP2pManager.connect()
    private fun connectToPeer(deviceAddress: String) {
        if (connectingToDeviceAddress == deviceAddress) {
            logD("connectToPeer: already attempting connect to $deviceAddress — ignoring duplicate request")
            return
        }
        connectingToDeviceAddress = deviceAddress

        handler.postDelayed({
            try {
                try {
                    manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { logD("cancelConnect success (pre-connect)") }
                        override fun onFailure(reason: Int) { logW("cancelConnect failed (pre-connect): $reason") }
                    })
                    Thread.sleep(200)
                } catch (e: Exception) {
                    logW("cancelConnect threw (pre-connect): ${e.message}")
                }

                val config = WifiP2pConfig().apply {
                    this.deviceAddress = deviceAddress
                    wps.setup = WpsInfo.PBC
                }

                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    ) != PackageManager.PERMISSION_GRANTED)
                ) {
                    connectingToDeviceAddress = null
                    return@postDelayed
                }

                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        logD("connect() onSuccess to $deviceAddress — waiting for connection broadcast")
                        logOnUi("Connecting to peer $deviceAddress (in progress)")
                    }
                    override fun onFailure(reason: Int) {
                        logW("connect() onFailure to $deviceAddress reason=$reason")
                        logOnUi("Connect to $deviceAddress failed: $reason")
                        if (reason == WifiP2pManager.BUSY) {
                            handler.postDelayed({ discoverPeers() }, 2000L)
                        } else {
                            handler.postDelayed({ discoverPeers() }, 1200L)
                        }
                        handler.postDelayed({ trySavedGoFallback() }, 1000L)
                        connectingToDeviceAddress = null
                    }
                })
            } catch (e: Exception) {
                logW("connectToPeer exception: ${e.message}")
                connectingToDeviceAddress = null
            }
        }, 120)
    }

    // -------------------------
    // Connection info handling (shared)
    // -------------------------
    private fun onConnectionInfo(info: WifiP2pInfo) {
        logD("onConnectionInfo(): info=$info")
        handler.post {
            try {
                if (info.groupFormed) {
                    val host = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    logD("Group formed. owner=$host isGroupOwner=${info.isGroupOwner}")

                    try {
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putString(PREF_LAST_GO, host).apply()
                        lastGroupOwnerAddress = host
                        logD("Saved last_go=$host to prefs")
                    } catch (e: Exception) {
                        logW("Failed to save last_go: ${e.message}")
                    }

                    if (parentContainer.visibility == View.VISIBLE) {
                        if (info.isGroupOwner) {
                            parentIsGroupOwner = true
                            tvParentStatus.text = "Parent status: group formed (owner). GO IP: $host"
                            logOnUi("Parent: group formed and I am owner: $host")
                            startServerIfNeeded()
                        } else {
                            parentIsGroupOwner = false
                            tvParentStatus.text = "Parent status: group formed (not owner)"
                            logOnUi("Parent: group formed but I'm not owner")
                        }
                    }

                    if (childContainer.visibility == View.VISIBLE) {
                        tvChildStatus.text = "Child status: group formed. GO: $host"
                        logOnUi("Child: group formed. GO: $host")
                        if (clientSocket == null || clientSocket?.isConnected == false) {
                            handler.postDelayed({
                                if (clientSocket == null || clientSocket?.isConnected == false) {
                                    logD("Child: initiating auto-connect to $host (socket)")
                                    startClientRetryLoop(host)
                                } else {
                                    logD("Child: already connected - skipping")
                                }
                            }, 400)
                        } else {
                            logD("Child: socket already connected; skipping startClientRetryLoop")
                        }
                    }
                } else {
                    logD("Group not formed")
                    parentIsGroupOwner = false
                    if (parentContainer.visibility == View.VISIBLE) tvParentStatus.text = "Parent status: no group formed"
                    if (childContainer.visibility == View.VISIBLE) tvChildStatus.text = "Child status: no group formed"
                }
            } catch (e: Exception) {
                logE("Exception in onConnectionInfo: ${e.message}")
            }
        }
    }

    // -------------------------
    // Peer list listener (used when peers changed)
    // -------------------------
    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        try {
            val deviceList = peerList.deviceList
            logD("requestPeers: found ${deviceList.size} peers")
            if (deviceList.isEmpty()) {
                logOnUi("Peers: none")
                trySavedGoFallback()
                return@PeerListListener
            }
            deviceList.forEach { dev ->
                logD("Peer: name='${dev.deviceName}' addr=${dev.deviceAddress} status=${dev.status}")
                logOnUi("Peer: ${dev.deviceName} / ${dev.deviceAddress} / status=${dev.status}")
            }

            if (childContainer.visibility == View.VISIBLE) {
                val connectedPeer = deviceList.firstOrNull { it.status == WifiP2pDevice.CONNECTED }
                if (connectedPeer != null) {
                    logD("Found peer with status CONNECTED (${connectedPeer.deviceAddress}). Requesting connection info (no connect()).")
                    try {
                        manager.requestConnectionInfo(channel) { info ->
                            logD("requestConnectionInfo (from peerListListener) -> $info")
                            onConnectionInfo(info)
                        }
                    } catch (e: Exception) {
                        logW("requestConnectionInfo threw: ${e.message}")
                        handler.postDelayed({ trySavedGoFallback() }, 700L)
                    }
                    return@PeerListListener
                }

                val availableOrInvited = deviceList.firstOrNull { it.status == WifiP2pDevice.AVAILABLE || it.status == WifiP2pDevice.INVITED }
                if (availableOrInvited != null) {
                    logD("Auto-connecting to available/invited peer ${availableOrInvited.deviceName} (${availableOrInvited.deviceAddress})")
                    connectToPeer(availableOrInvited.deviceAddress)
                    return@PeerListListener
                }

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedGo = prefs.getString(PREF_LAST_GO, null)
                if (!savedGo.isNullOrBlank() && (clientSocket == null || clientSocket?.isConnected == false)) {
                    logD("No connectable peers found — attempting direct reconnect to saved GO IP $savedGo")
                    startClientRetryLoop(savedGo)
                } else {
                    logD("No connectable peers and no saved GO (or already connected).")
                }
            }
        } catch (e: Exception) {
            logW("peerListListener error: ${e.message}")
        }
    }

    // Try a saved GO fallback if peers are missing / connect failed
    private fun trySavedGoFallback() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(PREF_LAST_GO, null)
            if (!saved.isNullOrBlank()) {
                if (clientSocket == null || clientSocket?.isConnected == false) {
                    logD("trySavedGoFallback: attempting saved GO $saved with backoff ${savedGoBackoffMs}ms")
                    handler.postDelayed({
                        if (clientSocket == null || clientSocket?.isConnected == false) {
                            startClientRetryLoop(saved)
                            savedGoBackoffMs = (savedGoBackoffMs * 2).coerceAtMost(maxSavedGoBackoff)
                        }
                    }, savedGoBackoffMs)
                } else {
                    logD("trySavedGoFallback: already connected, skipping")
                }
            } else {
                logD("trySavedGoFallback: no saved GO")
            }
        } catch (e: Exception) {
            logW("trySavedGoFallback error: ${e.message}")
        }
    }

    // -------------------------
    // BroadcastReceiver (inner so it can call Activity methods)
    // -------------------------
    inner class CombinedBroadcastReceiver : BroadcastReceiver() {
        private val tag = "COMBINED_BR"
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            Log.d(tag, "onReceive() action=$action")
            when (action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    Log.d(tag, "WIFI_P2P_CONNECTION_CHANGED_ACTION networkInfo=$networkInfo")
                    if (networkInfo != null && networkInfo.isConnected) {
                        try {
                            manager.requestConnectionInfo(channel) { info ->
                                Log.d(tag, "Got connection info: $info")
                                onConnectionInfo(info)
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "requestConnectionInfo failed: ${e.message}")
                        }
                    } else {
                        Log.d(tag, "Not connected")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(tag, "P2P peers changed")
                    requestPeersAndLog()
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.d(tag, "P2P state changed: $state")
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d(tag, "This device changed")
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    Log.d(tag, "P2P discovery changed: $discoveryState")
                    if (discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) {
                        discoveryRequested = true
                        logOnUi("Child: discovery started")
                    } else if (discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                        discoveryRequested = false
                        logOnUi("Child: discovery stopped")
                    }
                }
                else -> {
                    Log.d(tag, "Unhandled action: $action")
                }
            }
        }
    }

    // -------------------------
    // Logging helpers
    // -------------------------
    private fun logD(msg: String) { Log.d(TAG, msg) }
    private fun logW(msg: String) { Log.w(TAG, msg) }
    private fun logE(msg: String) { Log.e(TAG, msg) }
    private fun logOnUi(text: String) {
        Log.d(TAG, "UILOG: $text")
        handler.post {
            try {
                tvLog.append(text + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append log to UI: ${e.message}")
            }
        }
    }
}
