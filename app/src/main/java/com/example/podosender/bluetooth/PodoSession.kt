package com.example.podosender.bluetooth

import android.bluetooth.BluetoothSocket
import com.example.podosender.protocol.JsonObjectSplitter
import com.example.podosender.protocol.PodoCommand
import com.example.podosender.protocol.PodoFrame
import com.example.podosender.protocol.PodoJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** 수신기가 보낸 JSON 한 개: `{"code":96,"msg":"..."}` */
data class PodoMessage(val code: Int, val msg: String, val raw: JSONObject) {
    companion object {
        fun parse(json: String): PodoMessage? = try {
            val o = JSONObject(json)
            PodoMessage(o.getInt("code"), o.optString("msg", ""), o)
        } catch (_: JSONException) {
            null
        }
    }
}

/** 연결 확인(ACK) 결과: 전광판이 스스로 알려주는 이름. */
data class DisplayInfo(val name: String)

/**
 * 연결된 RFCOMM 소켓 하나에 대한 세션.
 * - 수신: 별도 코루틴이 읽어서 JSON 단위로 [incoming] 에 흘려보냄
 * - 송신: [send] 는 Mutex 로 직렬화 (사진/영상 같은 큰 프레임이 섞이지 않게)
 */
class PodoSession(
    private val socket: BluetoothSocket,
    private val onClosed: (reason: String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<PodoMessage>(extraBufferCapacity = 32)
    val incoming: SharedFlow<PodoMessage> = _incoming.asSharedFlow()

    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean get() = !closed.get()

    fun start() {
        scope.launch { readLoop() }
    }

    private suspend fun readLoop() {
        val splitter = JsonObjectSplitter()
        val buf = ByteArray(1024)
        val input = socket.inputStream
        try {
            while (true) {
                val n = input.read(buf) // 소켓을 닫으면 IOException 으로 깨어남
                if (n < 0) break
                for (json in splitter.feed(buf, n)) {
                    val msg = PodoMessage.parse(json) ?: continue
                    if (msg.code == PodoCommand.SOCKET_CLOSE) {
                        shutdown("전광판이 연결 종료를 요청했습니다.")
                        return
                    }
                    _incoming.emit(msg)
                }
            }
            shutdown("전광판에서 연결을 종료했습니다.")
        } catch (e: IOException) {
            shutdown(if (closed.get()) null else (e.message ?: "연결이 끊어졌습니다."))
        }
    }

    /** 원시 프레임 전송. 다음 단계(사진/영상)에서도 이 함수를 그대로 씁니다. */
    suspend fun send(command: Int, payload: ByteArray) {
        val frame = PodoFrame.encode(command, payload)
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    socket.outputStream.write(frame)
                    socket.outputStream.flush()
                } catch (e: IOException) {
                    shutdown(e.message ?: "전송 중 연결이 끊어졌습니다.")
                    throw e
                }
            }
        }
    }

    suspend fun sendJson(command: Int, msg: String, fields: List<Pair<String, Any>> = emptyList()) {
        send(command, PodoJson.build(command, msg, fields).toByteArray(Charsets.UTF_8))
    }

    /** ACK(95)를 보내고 전광판의 응답(96)에서 이름을 받습니다. 시간 안에 응답이 없으면 TimeoutCancellationException. */
    suspend fun handshake(timeoutMs: Long = ACK_TIMEOUT_MS): DisplayInfo = withTimeout(timeoutMs) {
        val reply = incoming
            .onSubscription { sendJson(PodoCommand.CLIENT_ACK_REQUEST, "") } // 구독 후 전송 → 응답을 놓치지 않음
            .first { it.code == PodoCommand.CLIENT_ACK_RESPONSE }
        DisplayInfo(name = reply.msg)
    }

    /** 종료(99)를 알린 뒤 닫습니다. */
    suspend fun closeGracefully(reason: String = "사용자 연결 해제") {
        if (closed.get()) return
        runCatching { withTimeout(1_000) { sendJson(PodoCommand.SOCKET_CLOSE, reason) } }
        shutdown(null)
    }

    /** 즉시 닫기(ViewModel 종료 등 코루틴을 쓸 수 없을 때). */
    fun abort() = shutdown(null)

    private fun shutdown(reason: String?) {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            scope.cancel()
            onClosed(reason)
        }
    }

    companion object {
        const val ACK_TIMEOUT_MS = 5_000L
    }
}
