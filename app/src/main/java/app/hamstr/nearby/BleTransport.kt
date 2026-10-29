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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hamstr in Bluetooth range, without a shared Wi-Fi. Each phone advertises Hamstr's service
 * with its id and runs a small GATT server: one characteristic to read who it is, one to write
 * a link into, in chunks. Other phones scan for the service, read a newcomer's name once and
 * write links into it. Only starts when Bluetooth is on and its permissions are granted;
 * [refresh] tries again after either changes.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context) : Transport {
    override val via = Via.Bluetooth

    private class Spot(val device: BluetoothDevice, val heard: Long)

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val spots = ConcurrentHashMap<String, Spot>()
    private val looked = ConcurrentHashMap<String, Long>()
    private val incoming = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val gattLock = Mutex()
    private var scope: CoroutineScope? = null
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
        scope.launch { Prefs.settings.map { it.nearbyBluetooth }.distinctUntilChanged().drop(1).collect { refresh() } }
        refresh()
    }

    override fun stop() {
        wanted = false
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

    override fun reaches(id: String) = spots[id]?.let { System.currentTimeMillis() - it.heard < 15_000 } == true

    override suspend fun send(id: String, message: ByteArray): Boolean {
        val device = spots[id]?.device ?: return false
        return gattLock.withLock {
            session(device) { s ->
                val inbox = s.characteristic(INBOX) ?: return@session false
                val size = (s.mtu - 3 - 1).coerceAtLeast(19)
                val chunks = message.toList().chunked(size)
                chunks.withIndex().all { (i, part) ->
                    val last = i == chunks.lastIndex
                    s.write(inbox, byteArrayOf(if (last) 1 else 0) + part.toByteArray())
                }
            } == true
        }
    }

    private fun up() {
        val adapter = manager?.adapter ?: return
        server = manager.openGattServer(context, serverCallback)?.apply {
            addService(BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(BluetoothGattCharacteristic(INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                addCharacteristic(BluetoothGattCharacteristic(INBOX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
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
            AdvertiseData.Builder().addManufacturerData(MAKER, hex(Nearby.id)).setIncludeDeviceName(false).build(),
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
        incoming.clear()
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
        val raw = result.scanRecord?.getManufacturerSpecificData(MAKER)?.takeIf { it.size == 8 } ?: return
        val id = raw.joinToString("") { "%02x".format(it) }
        if (id == Nearby.id) return
        val now = System.currentTimeMillis()
        spots[id] = Spot(result.device, now)
        val known = Nearby.peers.value[id]
        if (known != null && known.key.isNotEmpty()) {
            Nearby.seen(id, known.name, known.kind, known.key, via)
            return
        }
        // A newcomer: read its name and key once, not on every advertisement.
        val address = result.device.address
        if (now - (looked[address] ?: 0) < 60_000) return
        looked[address] = now
        scope?.launch { lookUp(result.device) }
    }

    private suspend fun lookUp(device: BluetoothDevice) = gattLock.withLock {
        val hello = session(device) { s -> s.characteristic(INFO)?.let { s.read(it) } } ?: return@withLock
        val m = runCatching { JSONObject(String(hello, Charsets.UTF_8)) }.getOrNull() ?: return@withLock
        val kind = runCatching { DeviceKind.valueOf(m.optString("kind")) }.getOrDefault(DeviceKind.Phone)
        Nearby.seen(m.optString("id"), m.optString("name").take(60), kind, m.optString("key"), via)
    }

    // --- Server: others read who this is and write links in ------------------------------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) incoming.remove(device.address)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val value = Nearby.hello().toString().toByteArray(Charsets.UTF_8)
            val from = offset.coerceIn(0, value.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(from, value.size))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (c.uuid != INBOX || value == null || value.isEmpty()) status = BluetoothGatt.GATT_FAILURE
            else {
                val buf = incoming.getOrPut(device.address) { ByteArrayOutputStream() }
                buf.write(value, 1, value.size - 1)
                when {
                    buf.size() > MAX_MESSAGE -> { incoming.remove(device.address); status = BluetoothGatt.GATT_FAILURE }
                    value[0] == 1.toByte() -> {
                        incoming.remove(device.address)
                        if (!Nearby.receive(buf.toByteArray())) status = BluetoothGatt.GATT_FAILURE
                    }
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
        }
    }

    // --- Client: one connection at a time, its callbacks turned into suspending steps ----

    private suspend fun <T> session(device: BluetoothDevice, block: suspend (Session) -> T): T? {
        val s = Session()
        val gatt = device.connectGatt(context, false, s, BluetoothDevice.TRANSPORT_LE) ?: return null
        s.gatt = gatt
        return try {
            withTimeoutOrNull(20_000) {
                if (s.await(Step.Connect) != 0) return@withTimeoutOrNull null
                gatt.requestMtu(247)
                s.await(Step.Mtu)
                gatt.discoverServices()
                if (s.await(Step.Services) != 0) return@withTimeoutOrNull null
                block(s)
            }
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private enum class Step { Connect, Mtu, Services, Read, Write }

    private inner class Session : BluetoothGattCallback() {
        lateinit var gatt: BluetoothGatt
        var mtu = 23
        private var value: ByteArray? = null
        private val events = Channel<Pair<Step, Int>>(Channel.UNLIMITED)

        /** Waits for [step]; a dropped connection ends every wait with -1. */
        suspend fun await(step: Step): Int {
            while (true) {
                val (s, status) = events.receive()
                if (s == Step.Connect && step != Step.Connect) return -1
                if (s == step) return status
            }
        }

        fun characteristic(uuid: UUID) = gatt.getService(SERVICE)?.getCharacteristic(uuid)

        suspend fun read(c: BluetoothGattCharacteristic): ByteArray? {
            if (!gatt.readCharacteristic(c)) return null
            return if (await(Step.Read) == 0) value else null
        }

        @Suppress("DEPRECATION")
        suspend fun write(c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = bytes
                gatt.writeCharacteristic(c)
            }
            return started && await(Step.Write) == 0
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
        private val SERVICE: UUID = UUID.fromString("6d3a0001-5a1e-4c7b-9f3e-48616d737472")
        private val INFO: UUID = UUID.fromString("6d3a0002-5a1e-4c7b-9f3e-48616d737472")
        private val INBOX: UUID = UUID.fromString("6d3a0003-5a1e-4c7b-9f3e-48616d737472")
        // The Bluetooth SIG's id for tests and unregistered makers; the payload is Hamstr's 8-byte id.
        private const val MAKER = 0xFFFF
        private const val MAX_MESSAGE = 64 * 1024

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
