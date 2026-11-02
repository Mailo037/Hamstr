package app.hamstr.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import app.hamstr.Log
import app.hamstr.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hamstr in Bluetooth range, without a shared Wi-Fi. Each phone advertises Hamstr's service
 * with its id and runs its GATT server ([Ble] has what the characteristics mean, [BleServer]
 * answers them). Other devices scan for the service, read a newcomer's hello once, write links
 * into it and hold a stream open for files. A computer advertises the service without its id;
 * who it is comes from its hello. An advertisement only names an id, so anyone could repeat
 * it; a link counts as delivered only once its receipt checks out. Only starts when Bluetooth
 * is on and its permissions are granted; [refresh] tries again after either changes.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context) : StreamTransport {
    override val via = Via.Bluetooth
    override val chunk = Ble.CHUNK

    private class Spot(val device: BluetoothDevice, val heard: Long)

    private val manager = context.getSystemService(BluetoothManager::class.java)
    // Every device advertising an id lately, by its Bluetooth address.
    private val spots = ConcurrentHashMap<String, ConcurrentHashMap<String, Spot>>()
    // Ids learned from a hello, for devices that don't advertise theirs (computers).
    private val ids = ConcurrentHashMap<String, String>()
    private val looked = ConcurrentHashMap<String, Long>()
    private var answers = BleServer()
    private val gattLock = Mutex()
    private var scope: CoroutineScope? = null
    private var settingsJob: Job? = null
    private var server: BluetoothGattServer? = null
    private var advertising: AdvertiseCallback? = null
    private var scanning: ScanCallback? = null
    @Volatile private var eager = false
    @Volatile private var wanted = false

    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        wanted = true
        instance = this
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(adapterState, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(adapterState, filter)
        settingsJob?.cancel()
        settingsJob = scope.launch { Prefs.settings.map { it.nearbyBluetooth }.distinctUntilChanged().drop(1).collect { refresh() } }
        refresh()
    }

    override fun stop() {
        wanted = false
        settingsJob?.cancel()
        settingsJob = null
        eager = false
        runCatching { context.unregisterReceiver(adapterState) }
        down()
    }

    /** Starts or stops Bluetooth to match the setting, the adapter and the permissions. */
    fun refresh() {
        down()
        if (wanted && usable(context)) runCatching { up() }.onFailure { Log.w(TAG, "Bluetooth didn't start", it); down() }
    }

    override fun discover(on: Boolean) {
        if (eager == on) return
        eager = on
        if (scanning != null) { stopScan(); startScan() }
    }

    private fun places(id: String): List<BluetoothDevice> {
        val now = System.currentTimeMillis()
        return spots[id]?.values?.filter { now - it.heard < 15_000 }?.sortedByDescending { it.heard }?.map { it.device }.orEmpty()
    }

    override fun reaches(id: String) = places(id).isNotEmpty()

    override suspend fun send(id: String, message: ByteArray, accept: (ByteArray) -> Boolean): Boolean {
        for (device in places(id)) {
            val ok = gattLock.withLock { session(device) { s -> Ble.send(s, message, accept) } == true }
            if (ok) return true
        }
        return false
    }

    override suspend fun exchange(id: String, block: (Wire) -> Boolean): Boolean {
        for (device in places(id)) {
            // Files hold the connection as long as they take; links wait for it meanwhile.
            val ok = gattLock.withLock {
                session(device, within = null) { s ->
                    s.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    block(Ble.wire(s))
                } == true
            }
            if (ok) return true
        }
        return false
    }

    private fun up() {
        val adapter = manager?.adapter ?: return
        answers = BleServer()
        server = manager.openGattServer(context, serverCallback)?.apply {
            addService(BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(BluetoothGattCharacteristic(INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                addCharacteristic(BluetoothGattCharacteristic(INBOX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
                addCharacteristic(BluetoothGattCharacteristic(RECEIPT, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                addCharacteristic(BluetoothGattCharacteristic(
                    STREAM,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
                ))
            })
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) = Log.w(TAG, "Advertising failed: $errorCode")
        }
        advertiser.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),
            AdvertiseData.Builder().addManufacturerData(Ble.MAKER, hex(Nearby.id)).setIncludeDeviceName(false).build(),
            cb,
        )
        advertising = cb
        startScan()
    }

    private fun down() {
        val adapter = manager?.adapter
        runCatching { advertising?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertising = null
        stopScan()
        runCatching { server?.close() }
        server = null
        spots.clear()
    }

    private fun startScan() {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) = found(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::found)
            override fun onScanFailed(errorCode: Int) = Log.w(TAG, "Scan failed: $errorCode")
        }
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(if (eager) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER).build(),
                cb,
            )
            scanning = cb
        }.onFailure { Log.w(TAG, "Couldn't scan", it) }
    }

    private fun stopScan() {
        runCatching { scanning?.let { manager?.adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanning = null
    }

    private fun found(result: ScanResult) {
        val address = result.device.address
        val raw = result.scanRecord?.getManufacturerSpecificData(Ble.MAKER)?.takeIf { it.size == 8 }
        val id = raw?.joinToString("") { "%02x".format(it) } ?: ids[address]
        if (id == Nearby.id) return
        val now = System.currentTimeMillis()
        if (id != null) {
            spot(id, result.device, now)
            val known = Nearby.peers.value[id]
            if (known != null && known.key.isNotEmpty()) {
                Nearby.seen(id, known.name, known.kind, known.key, via)
                return
            }
        }
        // A newcomer, or a computer that only advertises the service: read its hello once, not on every advertisement.
        if (now - (looked[address] ?: 0) < 60_000) return
        looked[address] = now
        scope?.launch { lookUp(result.device) }
    }

    private fun spot(id: String, device: BluetoothDevice, now: Long) {
        spots.getOrPut(id) { ConcurrentHashMap() }.apply {
            put(device.address, Spot(device, now))
            values.removeIf { now - it.heard > 60_000 }
        }
    }

    private suspend fun lookUp(device: BluetoothDevice) = gattLock.withLock {
        val peer = session(device) { s -> Ble.hello(s) } ?: return@withLock
        ids[device.address] = peer.id
        spot(peer.id, device, System.currentTimeMillis())
        Nearby.seen(peer.id, peer.name, peer.kind, peer.key, via)
    }

    // --- Server: others read who this is and write links and files in -------------------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) answers.disconnected(device.address)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val value = answers.read(device.address, c.uuid.toString(), offset)
            server?.sendResponse(device, requestId, if (value != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, value ?: ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            // Hamstr's pieces each fit in one write; a long (prepared) write isn't one of them.
            val ok = !preparedWrite && offset == 0 && value != null && answers.write(device.address, c.uuid.toString(), value)
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, null)
        }
    }

    // --- Client: one connection at a time, its callbacks turned into suspending steps ----

    /** Connects to [device] and runs [block]; [within] bounds the whole session, null only the connecting. */
    private suspend fun <T> session(device: BluetoothDevice, within: Long? = 20_000, block: suspend (Session) -> T): T? {
        val s = Session()
        val gatt = device.connectGatt(context, false, s, BluetoothDevice.TRANSPORT_LE) ?: return null
        s.gatt = gatt
        return try {
            val ready = withTimeoutOrNull(20_000) {
                if (s.await(Step.Connect) != 0) return@withTimeoutOrNull false
                gatt.requestMtu(517)
                s.await(Step.Mtu)
                gatt.discoverServices()
                s.await(Step.Services) == 0
            } == true
            if (!ready) null
            else if (within == null) block(s)
            else withTimeoutOrNull(within) { block(s) }
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private enum class Step { Connect, Mtu, Services, Read, Write }

    private inner class Session : BluetoothGattCallback(), GattLink {
        lateinit var gatt: BluetoothGatt
        var mtu = 23
        private var value: ByteArray? = null
        private val events = Channel<Pair<Step, Int>>(Channel.UNLIMITED)

        override val writeSize get() = minOf(mtu - 3, 512)
        override val fastWrites get() =
            find(STREAM)?.let { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 } == true

        /** Waits for [step]; a dropped connection ends every wait with -1. */
        suspend fun await(step: Step): Int {
            while (true) {
                val (s, status) = events.receive()
                if (s == Step.Connect && step != Step.Connect) return -1
                if (s == step) return status
            }
        }

        fun find(uuid: UUID) = gatt.getService(SERVICE)?.getCharacteristic(uuid)

        override suspend fun read(characteristic: String): ByteArray? {
            val c = find(UUID.fromString(characteristic)) ?: return null
            if (!gatt.readCharacteristic(c)) return null
            return if (await(Step.Read) == 0) value else null
        }

        @Suppress("DEPRECATION")
        override suspend fun write(characteristic: String, bytes: ByteArray, fast: Boolean): Boolean {
            val c = find(UUID.fromString(characteristic)) ?: return false
            val type = if (fast) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // A full queue says busy at once; it drains within a connection event or two.
            repeat(50) {
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
                } else {
                    c.writeType = type
                    c.value = bytes
                    gatt.writeCharacteristic(c)
                }
                if (started) return await(Step.Write) == 0
                kotlinx.coroutines.delay(10)
            }
            return false
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            events.trySend(Step.Connect to if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) 0 else -1)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            events.trySend(Step.Mtu to status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.trySend(Step.Services to status) }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.trySend(Step.Write to status) }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            this.value = value
            events.trySend(Step.Read to status)
        }

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            value = c.value
            events.trySend(Step.Read to status)
        }
    }

    companion object {
        private const val TAG = "Nearby"
        private val SERVICE: UUID = UUID.fromString(Ble.SERVICE)
        private val INFO: UUID = UUID.fromString(Ble.INFO)
        private val INBOX: UUID = UUID.fromString(Ble.INBOX)
        private val RECEIPT: UUID = UUID.fromString(Ble.RECEIPT)
        private val STREAM: UUID = UUID.fromString(Ble.STREAM)

        /** The one that is running, so the app can [refresh] it once a permission is granted. */
        var instance: BleTransport? = null
            private set

        /** The runtime permissions Bluetooth needs on this Android version. */
        val permissions: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun granted(context: Context) = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        fun adapterOn(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

        fun usable(context: Context) = Prefs.settings.value.nearbyBluetooth && granted(context) && adapterOn(context)

        private fun hex(id: String) = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
