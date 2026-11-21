package com.mycro.cinegl.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 影调名字跟随界面语言：简体中文界面用中文名，其他语言用英文名；导入的 LUT 始终用文件名。 */
class LookInfoTest {

    private val tealOrange = LookInfo(1, "teal_orange", "青橙", "Teal & Orange", isCube = false)
    private val cyan = LookInfo(11, "cube:Cyan.cube", "Cyan", "Cyan Fade", isCube = true)
    private val untitled = LookInfo(12, "cube:mine.cube", "我的胶片", "", isCube = true)

    @Test
    fun builtinLookFollowsUiLanguage() {
        assertEquals("青橙", tealOrange.title(zh = true))
        assertEquals("Teal & Orange", tealOrange.subtitle(zh = true))
        assertEquals("Teal & Orange", tealOrange.title(zh = false))
        assertNull(tealOrange.subtitle(zh = false))   // 英文界面不配中文小字
    }

    @Test
    fun importedLutKeepsFileName() {
        assertEquals("Cyan", cyan.title(zh = true))
        assertEquals("Cyan", cyan.title(zh = false))
        assertEquals("Cyan Fade", cyan.subtitle(zh = false))   // .cube 里的 TITLE
        assertEquals("我的胶片", untitled.title(zh = false))
        assertNull(untitled.subtitle(zh = true))   // 没有 TITLE：界面显示「导入的 LUT / Imported LUT」
    }

    @Test
    fun badgeBeforeThumbnailArrives() {
        assertEquals("青橙", tealOrange.badge(zh = true))
        assertEquals("TO", tealOrange.badge(zh = false))
        assertEquals("N", LookInfo(0, "natural", "原生", "Natural", isCube = false).badge(zh = false))
        assertEquals("BB", LookInfo(9, "bleach", "漂白", "Bleach Bypass", isCube = false).badge(zh = false))
        assertEquals("我的", untitled.badge(zh = true))
    }

    @Test
    fun cubeErrorCodesMatchCore() {
        // 数值和 C++ cinegl::CubeError 一致
        assertEquals(CubeError.SIZE_OUT_OF_RANGE, CubeError.of(1))
        assertEquals(CubeError.COUNT_MISMATCH, CubeError.of(6))
        assertEquals(CubeError.MISSING_SIZE, CubeError.of(8))
        assertEquals(CubeError.UNKNOWN, CubeError.of(0))
        assertEquals(CubeError.UNKNOWN, CubeError.of(42))
    }
}
