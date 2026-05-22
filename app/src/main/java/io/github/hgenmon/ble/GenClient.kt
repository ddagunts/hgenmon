package io.github.hgenmon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import io.github.hgenmon.AppLog
import io.github.hgenmon.protocol.BleSpec
import io.github.hgenmon.protocol.Frame
import io.github.hgenmon.protocol.Z44Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE client for the generator BT module. Uses the API-33+ BLE surface (value passed
 * to callbacks, status codes returned from writes) so it has no `@Suppress("DEPRECATION")`.
 *
 * Thread-safety: GATT operations are serialised via [opMutex] because Android's BLE stack
 * accepts one outstanding request at a time per connection.
 */
class GenClient(private val context: Context) {

    private val state = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingRead: CompletableDeferred<ByteArray?>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Boolean>? = null
    private var pendingServicesDiscovered: CompletableDeferred<Boolean>? = null
    private var pendingConnect: CompletableDeferred<Boolean>? = null
    private val notifyHandlers = mutableMapOf<UUID, (ByteArray) -> Unit>()
    private val diagnosticResponseQueue = ArrayDeque<CompletableDeferred<Frame?>>()

    /** Items that have failed enough times this session to be skipped until reconnect. */
    private val pollBlacklist = mutableSetOf<Z44Profile.DataItem>()
    /** Consecutive-failure counter per item — reset on success, increment on no-response. */
    private val pollFailStreak = mutableMapOf<Z44Profile.DataItem, Int>()

    private companion object {
        /** Failures-before-blacklist threshold. */
        const val POLL_FAIL_BLACKLIST_AT = 3
        /** Items we never blacklist (core poll items; the empty-cycle detector handles real link death). */
        val CORE_ITEMS = setOf(Z44Profile.DataItem.OUTPUT_POWER, Z44Profile.DataItem.ENGINE_HOURS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            AppLog.i("client: conn state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    pendingConnect?.complete(status == BluetoothGatt.GATT_SUCCESS)
                    pendingConnect = null
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    state.value = State()
                    pendingConnect?.complete(false); pendingConnect = null
                    pendingRead?.complete(null); pendingRead = null
                    pendingWrite?.complete(false); pendingWrite = null
                    pendingDescriptorWrite?.complete(false); pendingDescriptorWrite = null
                    pendingServicesDiscovered?.complete(false); pendingServicesDiscovered = null
                    while (diagnosticResponseQueue.isNotEmpty()) {
                        diagnosticResponseQueue.removeFirst().complete(null)
                    }
                    g.close()
                    gatt = null
                    notifyHandlers.clear()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            AppLog.i("client: services discovered status=$status (${g.services.size} svcs)")
            for (s in g.services) AppLog.i("  svc ${s.uuid}")
            pendingServicesDiscovered?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingServicesDiscovered = null
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null)
            pendingRead = null
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.w("client: onCharacteristicWrite ${c.uuid} status=$status (non-success)")
            }
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingWrite = null
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingDescriptorWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingDescriptorWrite = null
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            notifyHandlers[c.uuid]?.invoke(value.copyOf())
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = opMutex.withLock {
        if (gatt != null) {
            AppLog.w("client: already connected")
            return@withLock false
        }
        pollBlacklist.clear()
        pollFailStreak.clear()
        AppLog.i("client: connecting to ${device.address}")
        pendingConnect = CompletableDeferred()
        gatt = device.connectGatt(context, false, gattCallback)
        if (gatt == null) {
            AppLog.e("client: connectGatt returned null (Bluetooth off?)")
            pendingConnect = null
            return@withLock false
        }
        val connectOk = withTimeoutOrNull(15_000L) { pendingConnect!!.await() } ?: false
        pendingConnect = null
        if (!connectOk) {
            AppLog.e("client: connect failed or timed out")
            gatt?.close()
            gatt = null
            return@withLock false
        }

        pendingServicesDiscovered = CompletableDeferred()
        gatt!!.discoverServices()
        if (withTimeoutOrNull(5000L) { pendingServicesDiscovered!!.await() } != true) {
            AppLog.e("client: discoverServices failed")
            return@withLock false
        }

        val driveSub = enableIndication(BleSpec.Char.ENGINE_DRIVE_STATUS, ::handleDriveStatus)
        AppLog.i("client: ENGINE_DRIVE_STATUS indication ${if (driveSub) "ok" else "FAIL"}")
        val rspSub = enableIndication(BleSpec.Char.DIAGNOSTIC_RESPONSE, ::handleDiagnosticResponse)
        AppLog.i("client: DIAGNOSTIC_RESPONSE indication ${if (rspSub) "ok" else "FAIL"}")
        val ewiSub = enableIndication(BleSpec.Char.ERROR_AND_WARNING_INFO, ::handleErrorWarningInfo)
        AppLog.i("client: ERROR_AND_WARNING_INFO indication ${if (ewiSub) "ok" else "FAIL"}")

        val resetOk = writeCharacteristic(BleSpec.Char.UNLOCK_PROTECT, Z44Profile.unlockReset)
        AppLog.i("client: unlock reset ${if (resetOk) "ok" else "FAIL"}")
        val unlockOk = writeCharacteristic(BleSpec.Char.UNLOCK_PROTECT, Z44Profile.unlockPayload())
        AppLog.i("client: unlock pwd ${if (unlockOk) "ok" else "FAIL"}")

        val serialBytes = readCharacteristic(BleSpec.Char.FRAME_NUMBER)
        val serial = serialBytes?.let { Z44Profile.parseSerial(it) }
        AppLog.i("client: serial=${serial ?: "<read failed>"}")

        val driveBytes = readCharacteristic(BleSpec.Char.ENGINE_DRIVE_STATUS)
        val initialDrive = if (driveBytes != null && driveBytes.size >= 2) {
            Z44Profile.DriveState.fromByte(driveBytes[1].toInt()).also {
                AppLog.i("client: initial drive=${driveBytes.joinToString(",") { b -> "%02x".format(b) }} -> $it")
            }
        } else Z44Profile.DriveState.UNKNOWN

        state.value = state.value.copy(
            connected = true,
            serial = serial,
            address = device.address,
            driveState = initialDrive,
        )
        true
    }

    @SuppressLint("MissingPermission")
    suspend fun poll(item: Z44Profile.DataItem): Float? {
        val raw = pollRaw(item) ?: return null
        val display = item.toDisplay(raw)
        AppLog.i("poll: ${item.name} raw=$raw -> $display${item.unit}")
        if (item == Z44Profile.DataItem.OUTPUT_POWER) {
            state.value = state.value.copy(lastRefreshAt = System.currentTimeMillis())
        }
        return display
    }

    /**
     * Poll an item but return the raw assembled integer (for bitfields like WARNING/FAULT
     * where the scaled display value is meaningless).
     *
     * If the item is in the per-session blacklist (failed once already), returns null without
     * touching the wire. Failures here add the item to the blacklist so a single timeout doesn't
     * cause us to keep poisoning the diagnostic channel every cycle.
     */
    @SuppressLint("MissingPermission")
    suspend fun pollRaw(item: Z44Profile.DataItem): Int? = opMutex.withLock {
        if (gatt == null || !state.value.connected) {
            AppLog.w("poll: ${item.name} skipped (not connected)")
            return@withLock null
        }
        if (item in pollBlacklist) {
            return@withLock null
        }
        val bytes = mutableListOf<Int>()
        var idx = 0
        for (frame in item.readFrames()) {
            // Small inter-frame pace: some BT module firmwares reject the next write if it
            // arrives before they've finished sending the previous response indication.
            if (idx++ > 0) delay(80)
            val response = awaitDiagnosticResponse {
                writeCharacteristic(BleSpec.Char.DIAGNOSTIC_COMMAND, frame.encode())
            }
            if (response == null) {
                val streak = (pollFailStreak[item] ?: 0) + 1
                pollFailStreak[item] = streak
                val willBlacklist = streak >= POLL_FAIL_BLACKLIST_AT && item !in CORE_ITEMS
                AppLog.w(
                    "poll: ${item.name} ${frame.group}${frame.dataNo} no response (streak=$streak)" +
                        if (willBlacklist) " — blacklisting for this session" else ""
                )
                if (willBlacklist) pollBlacklist.add(item)
                return@withLock null
            }
            bytes.add(response.data.toInt(16))
        }
        pollFailStreak.remove(item)
        val raw = item.assemble(bytes)
        AppLog.i("poll: ${item.name} bytes=${bytes.joinToString { "%02x".format(it) }} raw=0x%x".format(raw))
        if (item == Z44Profile.DataItem.WARNING) {
            state.value = state.value.copy(warningBits = raw)
        } else if (item == Z44Profile.DataItem.FAULT) {
            state.value = state.value.copy(faultBits = raw)
        }
        raw
    }

    @SuppressLint("MissingPermission")
    suspend fun stopEngine(): Boolean = opMutex.withLock {
        AppLog.i("client: stopEngine")
        var ok = true
        repeat(7) {
            ok = ok && writeCharacteristic(BleSpec.Char.ENGINE_CONTROL, Z44Profile.engineStopByte)
            delay(100)
        }
        ok
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        AppLog.i("client: disconnect")
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        notifyHandlers.clear()
        diagnosticResponseQueue.clear()
        state.value = State()
    }

    // -- internal helpers --

    private suspend fun awaitDiagnosticResponse(send: suspend () -> Boolean): Frame? {
        val deferred = CompletableDeferred<Frame?>()
        diagnosticResponseQueue.addLast(deferred)
        if (!send()) {
            diagnosticResponseQueue.remove(deferred)
            return null
        }
        val result = withTimeoutOrNull(3000L) { deferred.await() }
        if (result == null) {
            diagnosticResponseQueue.remove(deferred)
            AppLog.w("poll: response timed out (3s)")
        }
        return result
    }

    private fun handleDiagnosticResponse(payload: ByteArray) {
        if (payload.isEmpty() || payload[0] != Frame.RESPONSE_DIRECTION) {
            AppLog.w("rsp: bad direction byte")
            return
        }
        val frame = Frame.decode(payload.copyOfRange(1, payload.size))
        val deferred = diagnosticResponseQueue.removeFirstOrNull()
        if (deferred == null) {
            AppLog.w("rsp: response arrived with empty queue (bytes=${payload.joinToString(",") { "%02x".format(it) }})")
            return
        }
        if (frame == null) {
            AppLog.w("rsp: undecodable, failing request (bytes=${payload.joinToString(",") { "%02x".format(it) }})")
            deferred.complete(null)
            return
        }
        deferred.complete(frame)
    }

    private fun handleDriveStatus(payload: ByteArray) {
        AppLog.i("drive: bytes=${payload.joinToString(",") { "%02x".format(it) }}")
        if (payload.size < 2) return
        val drive = Z44Profile.DriveState.fromByte(payload[1].toInt())
        state.value = state.value.copy(driveState = drive)
    }

    private fun handleErrorWarningInfo(payload: ByteArray) {
        val hex = payload.joinToString(" ") { "%02x".format(it) }
        AppLog.w("ewi: bytes=$hex")
        state.value = state.value.copy(ewiPayload = payload.copyOf(), ewiAt = System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristic(uuid: UUID, value: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = findChar(uuid) ?: run {
            AppLog.w("client: characteristic not found $uuid")
            return false
        }
        pendingWrite = CompletableDeferred()
        val rc = g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            AppLog.e("client: writeCharacteristic rc=$rc for $uuid")
            pendingWrite = null
            return false
        }
        return withTimeoutOrNull(5000L) { pendingWrite!!.await() } ?: false
    }

    @SuppressLint("MissingPermission")
    private suspend fun readCharacteristic(uuid: UUID): ByteArray? {
        val g = gatt ?: return null
        val c = findChar(uuid) ?: run {
            AppLog.w("client: characteristic not found $uuid")
            return null
        }
        pendingRead = CompletableDeferred()
        if (!g.readCharacteristic(c)) {
            AppLog.e("client: readCharacteristic returned false for $uuid")
            pendingRead = null
            return null
        }
        return withTimeoutOrNull(5000L) { pendingRead!!.await() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableIndication(uuid: UUID, handler: (ByteArray) -> Unit): Boolean {
        val g = gatt ?: return false
        val c = findChar(uuid) ?: return false
        notifyHandlers[uuid] = handler
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(BleSpec.CCCD) ?: return false
        pendingDescriptorWrite = CompletableDeferred()
        val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            AppLog.e("client: writeDescriptor rc=$rc for $uuid")
            pendingDescriptorWrite = null
            return false
        }
        return withTimeoutOrNull(5000L) { pendingDescriptorWrite!!.await() } ?: false
    }

    private fun findChar(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    data class State(
        val connected: Boolean = false,
        val address: String? = null,
        val serial: String? = null,
        val driveState: Z44Profile.DriveState = Z44Profile.DriveState.UNKNOWN,
        /** Epoch ms when the last successful OUTPUT_POWER poll completed. */
        val lastRefreshAt: Long? = null,
        /** Raw WARNING byte (group C / dataNo 10). Non-zero = at least one warning active. */
        val warningBits: Int = 0,
        /** Raw FAULT 16-bit value (group D / dataNos 10,11). Non-zero = at least one fault active. */
        val faultBits: Int = 0,
        /** Last raw payload received on ERROR_AND_WARNING_INFO, if any. */
        val ewiPayload: ByteArray? = null,
        /** Epoch ms when the last EWI payload arrived. */
        val ewiAt: Long? = null,
    ) {
        // Generated equals/hashCode skipped on ByteArray fields — we don't compare by content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return connected == other.connected
                && address == other.address
                && serial == other.serial
                && driveState == other.driveState
                && lastRefreshAt == other.lastRefreshAt
                && warningBits == other.warningBits
                && faultBits == other.faultBits
                && ewiAt == other.ewiAt
                && (ewiPayload?.contentEquals(other.ewiPayload) ?: (other.ewiPayload == null))
        }

        override fun hashCode(): Int {
            var r = connected.hashCode()
            r = 31 * r + (address?.hashCode() ?: 0)
            r = 31 * r + (serial?.hashCode() ?: 0)
            r = 31 * r + driveState.hashCode()
            r = 31 * r + (lastRefreshAt?.hashCode() ?: 0)
            r = 31 * r + warningBits
            r = 31 * r + faultBits
            r = 31 * r + (ewiAt?.hashCode() ?: 0)
            r = 31 * r + (ewiPayload?.contentHashCode() ?: 0)
            return r
        }
    }
}
