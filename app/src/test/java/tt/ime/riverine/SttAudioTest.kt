package tt.ime.riverine

import tt.ime.riverine.core.SttAudio
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADTS header 係手砌嘅 bit packing —— 砌錯一個 bit，Gemini 只會回一句
 * 「聽唔到內容」，喺機上面查會查到死。所以逐個 byte 釘死喺度。
 *
 * 格式（ISO/IEC 13818-7，7 byte、冇 CRC）：
 * ```
 * FF F1 | profile(2) freq(4) ch(1..) | ch(..2) len(13) | fullness(11) blocks(2)
 * ```
 */
class SttAudioTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `16kHz mono 嘅 index 係 8`() {
        assertEquals(8, SttAudio.freqIndex(16_000))
        assertEquals(3, SttAudio.freqIndex(48_000))
    }

    @Test
    fun `唔喺表入面嘅 sample rate 要話唔得`() {
        assertEquals(-1, SttAudio.freqIndex(17_000))
    }

    @Test
    fun `16kHz mono 一個 100 byte frame`() {
        // frameLength = 100 + 7 = 107 = 0b0_0000_0110_1011
        //   byte2 = profile-1(01) freq(1000) ch>>2(0)      = 0110 0000 = 0x60
        //   byte3 = ch&3(01)<<6 | len[12:11](00)           = 0100 0000 = 0x40
        //   byte4 = len[10:3] = 107 shr 3 = 13             = 0000 1101 = 0x0D
        //   byte5 = len[2:0](011)<<5 | fullness[10:6](1F)  = 0111 1111 = 0x7F
        //   byte6 = fullness[5:0](111111)<<2 | blocks-1(00)= 1111 1100 = 0xFC
        assertEquals("FF F1 60 40 0D 7F FC", hex(SttAudio.adtsHeader(100, 8)))
    }

    @Test
    fun `frame 大到要用埋最高兩個 bit`() {
        // 2048 + 7 = 2055 = 0b1_0000_0000_0111：len[12:11] = 01，要入到 byte3
        assertEquals("FF F1 60 41 00 FF FC", hex(SttAudio.adtsHeader(2048, 8)))
    }

    @Test
    fun `轉 sample rate 淨係郁到 byte2`() {
        // 48kHz（index 3）：byte2 = 01 0011 0 = 0x4C，其餘同上面一模一樣
        assertEquals("FF F1 4C 40 0D 7F FC", hex(SttAudio.adtsHeader(100, 3)))
    }

    @Test
    fun `header 永遠七個 byte`() {
        for (payload in listOf(1, 7, 255, 4096)) {
            assertEquals(7, SttAudio.adtsHeader(payload, 8).size)
        }
    }
}
