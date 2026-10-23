package com.example.podosender.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodoProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---- 아래 기대값은 기존 podopop_client.apk 의 sendMsg/sendImage 로직을 그대로 옮겨 실행해 얻은 값 ----

    @Test
    fun ackRequestFrameMatchesOriginalApp() {
        val frame = PodoFrame.encodeJson(PodoCommand.CLIENT_ACK_REQUEST, "")
        assertEquals("0000005f000000147b22636f6465223a39352c226d7367223a22227d", hex(frame))
    }

    @Test
    fun closeFrameUsesUtf8ByteLengthNotCharCount() {
        val frame = PodoFrame.encodeJson(PodoCommand.SOCKET_CLOSE, "종료 요청")
        assertEquals(
            "00000063000000217b22636f6465223a39392c226d7367223a22eca285eba38c20ec9a94ecb2ad227d",
            hex(frame),
        )
    }

    @Test
    fun rawImageFrame() {
        val frame = PodoFrame.encode(PodoCommand.SEND_PICTURE_IMAGE, byteArrayOf(1, 2, 3, 0xff.toByte()))
        assertEquals("0000002200000004010203ff", hex(frame))
    }

    @Test
    fun lengthFieldUsesAllFourBytes() {
        val frame = PodoFrame.encode(PodoCommand.SEND_VIDEO_IMAGE_BOARDING, ByteArray(70_000))
        assertEquals("0000004000011170", hex(frame.copyOfRange(0, 8)))
        assertEquals(8 + 70_000, frame.size)
    }

    // ---- JSON 생성 ----

    @Test
    fun jsonEscapesSpecialCharacters() {
        assertEquals("{\"code\":94,\"msg\":\"a\\\"b\\\\c\\n\\u0001\"}", PodoJson.build(94, "a\"b\\c\n\u0001"))
    }

    @Test
    fun jsonExtraFieldsKeepOrderAndTypes() {
        val s = PodoJson.build(30, "새 데이터", listOf("rid" to 3, "size" to 123456789012L, "regdate" to "2026-10-23 04:00:00"))
        assertEquals(
            "{\"code\":30,\"msg\":\"새 데이터\",\"rid\":3,\"size\":123456789012,\"regdate\":\"2026-10-23 04:00:00\"}",
            s,
        )
    }

    // ---- 수신 스트림 분리 ----

    @Test
    fun splitsTwoObjectsArrivingInOneRead() {
        val s = JsonObjectSplitter()
        val data = "{\"code\":96,\"msg\":\"A\"}{\"code\":10,\"msg\":\"B\"}".toByteArray()
        assertEquals(listOf("{\"code\":96,\"msg\":\"A\"}", "{\"code\":10,\"msg\":\"B\"}"), s.feed(data))
    }

    @Test
    fun reassemblesObjectSplitAcrossReads_evenInsideMultibyteChar() {
        val full = "{\"code\":96,\"msg\":\"포도 전광판\"}".toByteArray(Charsets.UTF_8)
        val s = JsonObjectSplitter()
        val cut = full.indexOf(0xed.toByte()) + 1 // 한글 첫 글자 바이트 중간에서 자름
        assertTrue(s.feed(full.copyOfRange(0, cut)).isEmpty())
        assertEquals(listOf("{\"code\":96,\"msg\":\"포도 전광판\"}"), s.feed(full.copyOfRange(cut, full.size)))
    }

    @Test
    fun bracesAndQuotesInsideStringsDoNotConfuseParser() {
        val s = JsonObjectSplitter()
        val json = "{\"code\":96,\"msg\":\"} \\\" { }\"}"
        assertEquals(listOf(json), s.feed(json.toByteArray()))
    }

    @Test
    fun nestedObjectsAndGarbageBetweenObjects() {
        val s = JsonObjectSplitter()
        val a = "{\"code\":14,\"msg\":\"x\",\"in\":{\"k\":1}}"
        val out = s.feed("\r\n$a\u0000\u0000{\"code\":1,\"msg\":\"\"}".toByteArray())
        assertEquals(listOf(a, "{\"code\":1,\"msg\":\"\"}"), out)
    }

    @Test
    fun oversizedGarbageObjectIsDroppedAndParserRecovers() {
        val s = JsonObjectSplitter(maxObjectBytes = 32)
        assertTrue(s.feed("{\"x\":\"${"a".repeat(100)}".toByteArray()).isEmpty())
        assertEquals(listOf("{\"code\":96,\"msg\":\"ok\"}"), s.feed("{\"code\":96,\"msg\":\"ok\"}".toByteArray()))
    }

    @Test
    fun feedHonoursLengthArgument() {
        val s = JsonObjectSplitter()
        val buf = ByteArray(1024)
        val msg = "{\"code\":96,\"msg\":\"n\"}".toByteArray()
        System.arraycopy(msg, 0, buf, 0, msg.size)
        assertEquals(1, s.feed(buf, msg.size).size)
    }

    @Test
    fun encodeDoesNotMutatePayload() {
        val p = byteArrayOf(9, 8, 7)
        PodoFrame.encode(1, p)
        assertArrayEquals(byteArrayOf(9, 8, 7), p)
    }
}
