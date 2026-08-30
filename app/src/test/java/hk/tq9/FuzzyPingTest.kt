package hk.tq9

import hk.tq9.core.Q9Db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 近音字靠 [Q9Db.fuzzyPing]：兩個 `ping` 揉到同一個結果就當近音
 * （`nearHomo` 就係攞呢個做 key 分組）。
 */
class FuzzyPingTest {

    private fun same(a: String, b: String) =
        assertEquals("$a 同 $b 應該當近音", Q9Db.fuzzyPing(a), Q9Db.fuzzyPing(b))

    private fun diff(a: String, b: String) =
        assertNotEquals("$a 同 $b 唔應該撞埋", Q9Db.fuzzyPing(a), Q9Db.fuzzyPing(b))

    @Test fun `ng 聲母甩得`() {
        same("ngo", "o")       // 我 ↔ 柯
        same("ngai", "ai")
        same("ngaan", "aan")
    }

    @Test fun `n 同 l 不分`() {
        same("naa", "laa")
        same("nei", "lei")     // 你 ↔ 李
        same("naa", "na")      // 乸 ↔ 那（連埋長短元音）
    }

    @Test fun `gw 同 g、kw 同 k 不分`() {
        same("gwong", "gong")  // 광 ↔ 江
        same("gwok", "gok")    // 國 ↔ 各
        same("kwok", "kok")
        same("kwaang", "kaang")
    }

    @Test fun `長短元音不分`() {
        same("saan", "san")
        same("baa", "ba")
    }

    @Test fun `鼻音同入聲韻尾相近`() {
        same("san", "sang")    // 新 ↔ 生
        same("baat", "baak")   // 八 ↔ 百
        same("jan", "jang")
    }

    /** 表入面夾雜咗粵拼串法嘅冷字，要同耶魯嗰啲夾得返 */
    @Test fun `粵拼同耶魯串法當同一個音`() {
        same("zi", "ji")       // 衹 ↔ 之
        same("ci", "chi")      // 黐 ↔ 此
        same("ceoi", "cheui")  // 綷 ↔ 取
        same("seoi", "seui")   // 陲 ↔ 水
        same("zoek", "jeuk")   // 妁 ↔ 雀
        same("soeng", "seung")
        same("deon", "deun")
    }

    @Test fun `硬寫嗰兩組`() {
        same("ngo", "a")       // 我 ↔ 啊（user 指定）
        same("ng", "m")        // 五 ↔ 唔
    }

    /** 唔可以乜都撞埋一齊 —— 淨係聲母／韻尾嗰幾條規矩先算數 */
    @Test fun `唔相干嘅音唔會撞埋`() {
        diff("si", "sa")
        diff("gam", "gan")     // -m 唔當同 -n 一樣
        diff("baat", "baap")   // -p 唔當同 -t 一樣
        diff("fu", "wu")
        diff("jyu", "yu")      // 主 ↔ 於，耶魯串法本來就唔同音
    }

    /** 淨鼻音字（五、唔）唔可以剝到剩返吉 */
    @Test fun `單鼻音字唔會揉到剩返吉`() {
        for (p in listOf("ng", "m", "hng")) {
            assertNotEquals("「$p」揉完變吉", "", Q9Db.fuzzyPing(p))
        }
    }
}
