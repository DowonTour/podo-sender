package com.example.podosender.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ConnectedDisplay(
    val session: PodoSession,
    /** 목록에서 보이던 블루투스 이름 */
    val btName: String?,
    val address: String,
    /** 전광판이 ACK(96)로 직접 알려준 이름 */
    val displayName: String,
)

/**
 * 선택한 기기에 RFCOMM(SPP) 으로 접속하고 ACK 핸드셰이크까지 끝낸 세션을 돌려줍니다.
 *
 * 기존 앱과 동일하게 "비보안(insecure) RFCOMM" 을 사용합니다. 전광판이 페어링 없이도 붙도록 만들어져 있어
 * 호환성을 위한 선택이며, 수신기를 직접 수정할 수 있다면 페어링(보안) 소켓 + 인증으로 바꾸는 것을 권장합니다.
 */
@SuppressLint("MissingPermission")
class PodoConnector(private val adapter: BluetoothAdapter) {

    @Throws(IOException::class)
    suspend fun connect(
        address: String,
        btName: String?,
        onClosed: (reason: String?) -> Unit,
    ): ConnectedDisplay {
        // 검색 중에는 연결이 매우 느려지거나 실패하므로 반드시 중단
        if (adapter.isDiscovering) adapter.cancelDiscovery()

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IOException("잘못된 블루투스 주소입니다: $address")
        }
        val socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        try {
            socket.awaitConnect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw IOException("연결하지 못했습니다. 전광판 전원/거리/다른 기기 연결 여부를 확인해 주세요. (${e.message})", e)
        }

        val session = PodoSession(socket, onClosed)
        session.start()
        val info = try {
            session.handshake()
        } catch (e: TimeoutCancellationException) {
            session.abort()
            throw IOException("기기가 응답하지 않습니다. PodoPop 전광판이 맞는지 확인해 주세요.")
        } catch (e: Throwable) {
            session.abort()
            throw e
        }
        return ConnectedDisplay(session, btName, address, info.name)
    }

    /** 블로킹 connect() 를 코루틴 취소와 연결: 취소되면 소켓을 닫아 connect() 가 즉시 풀리게 함. */
    private suspend fun BluetoothSocket.awaitConnect() = suspendCancellableCoroutine<Unit> { cont ->
        cont.invokeOnCancellation { runCatching { close() } }
        thread(name = "podo-bt-connect", isDaemon = true) {
            try {
                connect()
                cont.resume(Unit)
            } catch (e: IOException) {
                cont.resumeWithException(e)
            }
        }
    }

    companion object {
        /** SPP(Serial Port Profile) 표준 UUID — 기존 앱과 동일 */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
