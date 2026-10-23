package com.example.podosender.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.annotation.SuppressLint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.podosender.bluetooth.BtDevice
import com.example.podosender.bluetooth.ConnectedDisplay
import com.example.podosender.bluetooth.DeviceScanner
import com.example.podosender.bluetooth.PodoConnector
import com.example.podosender.bluetooth.ScanEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class ConnectedUi(val btName: String?, val address: String, val displayName: String)

data class UiState(
    val bluetoothSupported: Boolean = true,
    val devices: List<BtDevice> = emptyList(),
    val scanning: Boolean = false,
    /** 연결 시도 중인 기기 주소 */
    val connectingAddress: String? = null,
    val connected: ConnectedUi? = null,
    val message: String? = null,
)

@SuppressLint("MissingPermission") // 권한은 UI(MainActivity)에서 확인한 뒤에만 호출됨
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val adapter: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(UiState(bluetoothSupported = adapter != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var connection: ConnectedDisplay? = null

    private val devicesByAddress = LinkedHashMap<String, BtDevice>()

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun startScan() {
        val adapter = adapter ?: return
        if (_state.value.connected != null || _state.value.connectingAddress != null) return
        scanJob?.cancel()

        val scanner = DeviceScanner(getApplication(), adapter)
        devicesByAddress.clear()
        scanner.bondedDevices().forEach { devicesByAddress[it.address] = it }
        _state.update { it.copy(devices = sorted(), scanning = true, message = null) }

        scanJob = viewModelScope.launch {
            try {
                scanner.discover().collect { event ->
                    when (event) {
                        is ScanEvent.Found -> {
                            val d = event.device
                            val old = devicesByAddress[d.address]
                            // 이름 없이 다시 잡힌 경우 기존에 알던 이름을 지우지 않음
                            devicesByAddress[d.address] = d.copy(
                                name = d.name?.takeIf { it.isNotBlank() } ?: old?.name,
                                bonded = d.bonded || old?.bonded == true,
                            )
                            _state.update { it.copy(devices = sorted()) }
                        }
                        ScanEvent.Finished -> Unit
                    }
                }
                _state.update { it.copy(scanning = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(scanning = false) }
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(scanning = false, message = e.message) }
            } catch (e: SecurityException) {
                _state.update { it.copy(scanning = false, message = "블루투스 권한이 필요합니다.") }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun connect(device: BtDevice) {
        val adapter = adapter ?: return
        if (connectJob?.isActive == true || _state.value.connected != null) return
        stopScan()
        _state.update { it.copy(connectingAddress = device.address, message = null) }

        connectJob = viewModelScope.launch {
            try {
                val result = PodoConnector(adapter).connect(device.address, device.name) { reason ->
                    // 연결이 끊어졌을 때(IO 스레드에서 호출됨)
                    connection = null
                    _state.update { it.copy(connected = null, message = reason ?: it.message) }
                }
                if (!result.session.isOpen) throw IOException("연결 직후 기기가 연결을 종료했습니다.")
                connection = result
                _state.update {
                    it.copy(
                        connectingAddress = null,
                        connected = ConnectedUi(result.btName, result.address, result.displayName),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(connectingAddress = null) }
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(connectingAddress = null, message = e.message) }
            } catch (e: SecurityException) {
                _state.update { it.copy(connectingAddress = null, message = "블루투스 권한이 필요합니다.") }
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel()
    }

    fun disconnect() {
        val c = connection ?: return
        viewModelScope.launch { c.session.closeGracefully() }
    }

    fun showMessage(text: String?) = _state.update { it.copy(message = text) }

    override fun onCleared() {
        connectJob?.cancel()
        scanJob?.cancel()
        connection?.session?.abort()
    }

    private fun sorted(): List<BtDevice> =
        devicesByAddress.values.sortedWith(
            compareByDescending<BtDevice> { it.hasName }.thenByDescending { it.bonded }.thenBy { it.displayName.lowercase() },
        )
}
