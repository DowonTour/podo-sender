package com.example.podosender.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PodoPop 전광판 프로토콜 (기존 podopop_client.apk 분석 결과와 동일한 바이트 형식).
 *
 * 이 파일은 Android 의존성이 없는 순수 Kotlin 이라 JVM 단위 테스트로 검증됩니다.
 */
object PodoCommand {
    // 연결 / 제어
    const val START_CONNECT = 92
    const val RESPONSE_STANDBY = 93
    const val START_MESSAGE = 94
    const val CLIENT_ACK_REQUEST = 95
    const val CLIENT_ACK_RESPONSE = 96
    const val SOCKET_CLOSE = 99

    // 다음 단계(사진/영상 전송)에서 사용할 명령 — 기존 앱 기준
    const val SEND_PICTURE_DATA = 30      // JSON: 곧 사진을 보낸다고 알림
    const val RECEIVE_PICTURE_DATA = 32   // 수신기가 "받을 준비 됨"
    const val SEND_PICTURE_IMAGE = 34     // 본문 = 사진 원본 바이트
    const val SEND_VIDEO_DATA = 60
    const val RECEIVE_VIDEO_DATA = 62
    const val SEND_VIDEO_IMAGE_BOARDING = 64
    const val SEND_VIDEO_IMAGE_SUPPORT = 65
}

/**
 * 송신 프레임: `[명령 4B][본문 길이 4B][본문]`, 정수는 빅엔디언, 길이는 "바이트" 기준.
 */
object PodoFrame {
    const val HEADER_SIZE = 8

    fun encode(command: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(command)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    /** 제어 메시지: 본문이 JSON `{"code":<command>,"msg":"...", ...추가필드}` */
    fun encodeJson(
        command: Int,
        msg: String,
        fields: List<Pair<String, Any>> = emptyList(),
    ): ByteArray = encode(command, PodoJson.build(command, msg, fields).toByteArray(Charsets.UTF_8))
}

/** 의존성 없는 최소 JSON 문자열 생성기 (키 순서: code, msg, 추가필드 — 기존 앱과 동일). */
object PodoJson {
    fun build(code: Int, msg: String, fields: List<Pair<String, Any>> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("{\"code\":").append(code).append(",\"msg\":")
        quote(msg, sb)
        for ((k, v) in fields) {
            sb.append(',')
            quote(k, sb)
            sb.append(':')
            when (v) {
                is Int, is Long -> sb.append(v.toString())
                is Boolean -> sb.append(v.toString())
                else -> quote(v.toString(), sb)
            }
        }
        return sb.append('}').toString()
    }

    fun quote(s: String, sb: StringBuilder = StringBuilder()): StringBuilder {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"')
    }
}

/**
 * 수신 스트림 분리기.
 *
 * 기존 앱은 수신 쪽에 프레임 헤더 없이 "JSON 객체 하나 = read() 한 번"이라고 가정하지만,
 * RFCOMM 은 바이트 스트림이라 한 번에 두 개가 붙어 오거나 반으로 쪼개져 올 수 있습니다.
 * 그래서 중괄호 깊이(문자열/이스케이프 고려)로 최상위 `{...}` 객체를 잘라냅니다.
 * UTF-8 멀티바이트 문자는 모든 바이트가 0x80 이상이라 `{ } " \` 와 충돌하지 않습니다.
 */
class JsonObjectSplitter(private val maxObjectBytes: Int = 64 * 1024) {
    private val buf = ByteArrayOutputStream()
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun feed(data: ByteArray, length: Int = data.size): List<String> {
        val out = ArrayList<String>(2)
        for (i in 0 until length) {
            val b = data[i].toInt()
            if (depth == 0) {
                if (b == '{'.code) {
                    depth = 1
                    inString = false
                    escaped = false
                    buf.reset()
                    buf.write(b)
                }
                continue // 객체 밖의 바이트는 버림
            }
            buf.write(b)
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == '\\'.code -> escaped = true
                    b == '"'.code -> inString = false
                }
            } else {
                when (b) {
                    '"'.code -> inString = true
                    '{'.code -> depth++
                    '}'.code -> {
                        depth--
                        if (depth == 0) {
                            out.add(buf.toString(Charsets.UTF_8.name()))
                            buf.reset()
                        }
                    }
                }
            }
            if (buf.size() > maxObjectBytes) reset() // 비정상 데이터 방어
        }
        return out
    }

    fun reset() {
        buf.reset()
        depth = 0
        inString = false
        escaped = false
    }
}
