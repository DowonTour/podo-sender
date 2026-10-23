package com.example.podosender.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.podosender.bluetooth.BtPermissions

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { MainRoute(viewModel) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 화면을 떠나면 검색은 멈춤(배터리/다른 기기 연결 방해 방지). 연결 자체는 유지.
        if (!isChangingConfigurations) viewModel.stopScan()
    }
}

@Composable
private fun MainRoute(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    // 진행 순서: 권한 확인 → 블루투스 켜기 → 검색
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.isBluetoothOn()) vm.startScan() else vm.showMessage("블루투스를 켜야 기기를 찾을 수 있습니다.")
    }
    fun afterPermissionsGranted() {
        if (vm.isBluetoothOn()) vm.startScan() else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) afterPermissionsGranted()
        else vm.showMessage("블루투스 검색 권한이 거부되었습니다. 설정에서 허용해 주세요.")
    }
    fun requestScan() {
        if (BtPermissions.hasAll(context)) afterPermissionsGranted() else permissions.launch(BtPermissions.required())
    }

    LaunchedEffect(Unit) { if (state.bluetoothSupported && state.connected == null) requestScan() }

    MainScreen(
        state = state,
        onScan = { requestScan() },
        onStopScan = vm::stopScan,
        onSelect = vm::connect,
        onCancelConnect = vm::cancelConnect,
        onDisconnect = vm::disconnect,
        onDismissMessage = { vm.showMessage(null) },
    )
}
