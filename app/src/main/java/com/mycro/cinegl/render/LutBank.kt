package com.mycro.cinegl.render

import android.os.Handler
import com.mycro.cinegl.core.CineCore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 影调 LUT 的「烘焙 → 上传」调度。
 *
 * 烘焙（CPU 算 33³ 个格点）放在后台线程，上传（glTexSubImage3D）必须回到 GL 线程。
 * 工作信号的编码一变（开/关 Log），所有表都要重烘：表里已经包含了「解码 Log」这一步。
 * 用 generation 计数丢弃过期结果——切得快时，旧编码的表烘完了也不能再上传。
 *
 * 除构造外，所有方法都只能在 GL 线程调用。
 */
class LutBank(private val glHandler: Handler, private val engine: () -> Long) {
    private val baker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "CineGL-LutBaker") }
    private var encoding = -1
    private var generation = 0
    private val ready = sortedSetOf<Int>()

    /** 已经上传完、可以用的影调索引（升序）。 */
    val readyLooks: List<Int> get() = ready.toList()

    fun ensureEncoding(newEncoding: Int, priority: Int) {
        if (newEncoding == encoding) return
        encoding = newEncoding
        generation++
        ready.clear()
        CineCore.engineClearLookLuts(engine())
        // 中性 LUT 同步烘（约 10ms），保证切换的这一帧颜色就是对的
        CineCore.engineUploadNeutralLut(engine(), CineCore.bakeNeutral(newEncoding, CineCore.LUT_SIZE), CineCore.LUT_SIZE)
        val count = CineCore.lookCount()
        val order = listOf(priority) + (0 until count).filter { it != priority }
        order.filter { it in 0 until count }.forEach { bake(it) }
    }

    /** 烘一张（新导入的 .cube 也走这里）。 */
    fun bake(index: Int) {
        if (encoding < 0) return
        val gen = generation
        val enc = encoding
        baker.execute {
            val data = CineCore.bakeLook(index, enc, CineCore.LUT_SIZE) ?: return@execute
            glHandler.post {
                if (gen != generation) return@post
                CineCore.engineUploadLookLut(engine(), index, data, CineCore.LUT_SIZE)
                ready += index
            }
        }
    }

    fun release() {
        baker.shutdownNow()
        ready.clear()
    }
}
