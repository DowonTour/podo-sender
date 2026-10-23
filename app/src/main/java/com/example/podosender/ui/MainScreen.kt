package com.example.podosender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.podosender.bluetooth.BtDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (BtDevice) -> Unit,
    onCancelConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("PodoPop 전송") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            if (!state.bluetoothSupported) {
                Text("이 기기는 블루투스를 지원하지 않습니다.", Modifier.padding(16.dp))
                return@Column
            }

            state.connected?.let { c ->
                ConnectedCard(c, onDisconnect)
            }

            state.message?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismissMessage) { Text("닫기") }
                }
            }

            if (state.connected == null) {
                ScanBar(state, onScan, onStopScan, onCancelConnect)
                DeviceList(state, onSelect)
            }
        }
    }
}

@Composable
private fun ConnectedCard(c: ConnectedUi, onDisconnect: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("연결됨", style = MaterialTheme.typography.labelLarge)
            Text(c.displayName.ifBlank { c.btName ?: c.address }, style = MaterialTheme.typography.titleLarge)
            Text(c.address, style = MaterialTheme.typography.bodySmall)
            // 다음 단계: 갤러리 사진/영상 선택 후 전송
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("사진 · 영상 보내기 (다음 단계)")
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("연결 해제") }
        }
    }
}

@Composable
private fun ScanBar(state: UiState, onScan: () -> Unit, onStopScan: () -> Unit, onCancelConnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            state.connectingAddress != null -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("연결 중…", Modifier.weight(1f))
                TextButton(onClick = onCancelConnect) { Text("취소") }
            }
            state.scanning -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("주변 기기 검색 중…", Modifier.weight(1f))
                TextButton(onClick = onStopScan) { Text("중지") }
            }
            else -> {
                Text("기기 ${state.devices.size}개", Modifier.weight(1f))
                Button(onClick = onScan) { Text("다시 검색") }
            }
        }
    }
}

@Composable
private fun DeviceList(state: UiState, onSelect: (BtDevice) -> Unit) {
    val busy = state.connectingAddress != null
    if (state.devices.isEmpty()) {
        Text(
            if (state.scanning) "기기를 찾는 중입니다…" else "찾은 기기가 없습니다. 전광판 전원을 확인하고 다시 검색해 주세요.",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.devices, key = { it.address }) { d ->
            ListItem(
                headlineContent = { Text(d.displayName) },
                supportingContent = { Text(d.address) },
                trailingContent = {
                    when {
                        state.connectingAddress == d.address ->
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        d.bonded -> Text("페어링됨", style = MaterialTheme.typography.labelMedium)
                    }
                },
                modifier = Modifier.clickable(enabled = !busy) { onSelect(d) },
            )
            HorizontalDivider()
        }
    }
}
