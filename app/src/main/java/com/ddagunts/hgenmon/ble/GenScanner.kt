package com.ddagunts.hgenmon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.ddagunts.hgenmon.AppLog
import com.ddagunts.hgenmon.protocol.BleSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE scanner. Scans with NO filter so we can see everything advertising nearby; the
 * caller can decide which devices look like a generator.
 */
class GenScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun discoverFlow(): Flow<Discovered> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            AppLog.e("scan: BluetoothLeScanner not available")
            close(IllegalStateException("Bluetooth LE not available"))
            return@callbackFlow
        }
        if (adapter.state != BluetoothAdapter.STATE_ON) {
            AppLog.e("scan: adapter state=${adapter.state} (need STATE_ON=12)")
        }
        val seen = mutableSetOf<String>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address ?: return
                val name = result.device.name ?: result.scanRecord?.deviceName
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
                val isCompatible = uuids.any { it.equals(GENERATOR_DATA_SERVICE_STR, true) || it.equals(REMOTE_CONTROL_SERVICE_STR, true) }
                if (seen.add(addr)) {
                    AppLog.i("scan: $addr  rssi=${result.rssi}  name=${name ?: "?"}  compat=$isCompatible  svcs=${uuids.size}")
                    if (uuids.isNotEmpty()) AppLog.i("  svcs: ${uuids.joinToString()}")
                }
                trySend(Discovered(result.device, result.rssi, name, uuids, isCompatible))
            }
            override fun onScanFailed(errorCode: Int) {
                AppLog.e("scan: failed code=$errorCode")
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        AppLog.i("scan: starting (unfiltered, low-latency)")
        scanner.startScan(null, settings, callback)
        awaitClose {
            AppLog.i("scan: stopping")
            scanner.stopScan(callback)
        }
    }

    data class Discovered(
        val device: BluetoothDevice,
        val rssi: Int,
        val name: String?,
        val advertisedServices: List<String>,
        /** True if the advertised services match the generator BT module pattern. */
        val isCompatible: Boolean,
    )

    private companion object {
        val GENERATOR_DATA_SERVICE_STR = BleSpec.Service.GENERATOR_DATA.toString()
        val REMOTE_CONTROL_SERVICE_STR = BleSpec.Service.REMOTE_CONTROL.toString()
    }
}
