package com.example.podosender.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

sealed interface ScanEvent {
    data class Found(val device: BtDevice) : ScanEvent
    data object Finished : ScanEvent
}

/**
 * 클래식 블루투스 기기 검색.
 * 기존 앱과 같이 "이미 페어링된 기기 목록" + "주변 검색(discovery)" 두 가지를 합쳐서 사용합니다.
 *
 * 호출 전에 [BtPermissions.hasAll] 로 권한을 확인해야 합니다.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {
    fun bondedDevices(): List<BtDevice> =
        adapter.bondedDevices.orEmpty().map { BtDevice(it.address, it.name, bonded = true) }

    /** 검색이 끝나면(약 12초) [ScanEvent.Finished] 를 내보내고 종료됩니다. 수집을 취소하면 검색도 중단됩니다. */
    fun discover(): Flow<ScanEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val device = intent.bluetoothDevice() ?: return
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
                        trySend(
                            ScanEvent.Found(
                                BtDevice(device.address, name, device.bondState == BluetoothDevice.BOND_BONDED),
                            ),
                        )
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        trySend(ScanEvent.Finished)
                        close()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        if (adapter.isDiscovering) adapter.cancelDiscovery()
        if (!adapter.startDiscovery()) {
            close(IOException("블루투스 검색을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요."))
        }
        awaitClose {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
