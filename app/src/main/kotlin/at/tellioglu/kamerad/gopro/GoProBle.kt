package at.tellioglu.kamerad.gopro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class FoundCamera(val address: String, val name: String)

private fun Context.bluetoothAdapter() =
    getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("Bluetooth not available")

/** Scans for advertising GoPro cameras until the flow is cancelled. */
@SuppressLint("MissingPermission")
fun scanForCameras(context: Context): Flow<FoundCamera> = callbackFlow {
    val scanner = context.bluetoothAdapter().bluetoothLeScanner ?: throw IOException("Bluetooth is off")
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: result.device.address
            trySend(FoundCamera(result.device.address, name))
        }

        override fun onScanFailed(errorCode: Int) {
            close(IOException("Bluetooth scan failed ($errorCode)"))
        }
    }
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GoProUuids.ADVERTISED_SERVICE)).build()
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(listOf(filter), settings, callback)
    awaitClose { scanner.stopScan(callback) }
}

/** Bonds (pairs) with the camera; the camera must be in pairing mode. */
@SuppressLint("MissingPermission")
suspend fun bondWithCamera(context: Context, address: String) {
    val device = context.bluetoothAdapter().getRemoteDevice(address)
    if (device.bondState == BluetoothDevice.BOND_BONDED) return
    val bondStates = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address == address) {
                    trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        if (!device.createBond()) close(IOException("Pairing could not be started"))
        awaitClose { context.unregisterReceiver(receiver) }
    }
    val result = withTimeout(60.seconds) {
        bondStates.first { it == BluetoothDevice.BOND_BONDED || it == BluetoothDevice.BOND_NONE }
    }
    if (result != BluetoothDevice.BOND_BONDED) throw IOException("Pairing failed")
}

/**
 * Minimal GATT client: one connection, operations serialized and awaited one at a time.
 * Create a new instance per connection attempt and [close] it afterwards.
 */
@SuppressLint("MissingPermission")
/**
 * @param autoConnect false: a direct connection attempt, which is quick when the camera is advertising but
 *   gives up; true: Android's background connection, which waits patiently but can take much longer
 */
class GoProBle(private val context: Context, address: String, private val autoConnect: Boolean = true) {
    private val device = context.bluetoothAdapter().getRemoteDevice(address)
    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private val connected = CompletableDeferred<Unit>()

    /** Completes when the connection is lost or closed. */
    val disconnected = CompletableDeferred<Unit>()

    private val _notifications = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = 64)
    val notifications: SharedFlow<Pair<UUID, ByteArray>> = _notifications.asSharedFlow()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val error = IOException("Disconnected (status $status)")
                    connected.completeExceptionally(error)
                    pending?.completeExceptionally(error)
                    disconnected.complete(Unit)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            pending?.complete(status to null)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pending?.complete(status to characteristic.value?.copyOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status to null)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status to null)
        }

        // Also invoked on API 33+ in addition to the new overload
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { _notifications.tryEmit(characteristic.uuid to it.copyOf()) }
        }
    }

    /**
     * Connects (waiting until the camera is in range) and discovers services.
     * @param linkTimeout give up waiting for the camera to answer after this long (null: wait for ever)
     * @param onLinkUp called once the Bluetooth link is up, i.e. the camera has answered
     *   (it may still be booting: service discovery can take several seconds more)
     * @return false if the camera didn't answer within [linkTimeout]
     */
    suspend fun connect(linkTimeout: Duration? = null, onLinkUp: () -> Unit = {}): Boolean {
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        val answered = if (linkTimeout == null) {
            connected.await()
            true
        } else {
            withTimeoutOrNull(linkTimeout) {
                connected.await()
                true
            } ?: false
        }
        if (!answered) return false
        onLinkUp()
        op("Service discovery") { it.discoverServices() }
        return true
    }

    suspend fun write(uuid: UUID, value: ByteArray) {
        op("Write $uuid") { gatt ->
            val characteristic = gatt.characteristic(uuid)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    suspend fun enableNotifications(uuid: UUID) {
        op("Enable notifications $uuid") { gatt ->
            val characteristic = gatt.characteristic(uuid)
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(GoProUuids.CLIENT_CHARACTERISTIC_CONFIG)
                ?: throw IOException("No notification descriptor on $uuid")
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        pending?.completeExceptionally(IOException("Closed"))
        disconnected.complete(Unit)
    }

    private fun BluetoothGatt.characteristic(uuid: UUID): BluetoothGattCharacteristic =
        services.firstNotNullOfOrNull { service -> service.getCharacteristic(uuid) }
            ?: throw IOException("Characteristic $uuid not found")

    private suspend fun op(name: String, start: (BluetoothGatt) -> Boolean): ByteArray? = opMutex.withLock {
        val gatt = gatt ?: throw IOException("Not connected")
        if (disconnected.isCompleted) throw IOException("Disconnected")
        val result = CompletableDeferred<Pair<Int, ByteArray?>>()
        pending = result
        try {
            if (!start(gatt)) throw IOException("$name could not be started")
            val (status, value) = withTimeout(10.seconds) { result.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("$name failed (status $status)")
            value
        } finally {
            pending = null
        }
    }
}
