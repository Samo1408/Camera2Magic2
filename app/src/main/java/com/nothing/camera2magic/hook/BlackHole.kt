package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.Collections
import java.util.WeakHashMap

object BlackHole {
    data class BH(
        val surface: Surface,
        val surfaceTexture: SurfaceTexture,
        var activeCount: Boolean = false
    ) {
        fun release() {
            surface.release()
            surfaceTexture.release()
        }
    }

    // 任意相机/binder 线程可触达，必须包 synchronizedMap；遍历点（clear/originSurfaces）
    // 额外 synchronized(_oab)，Collections.synchronizedMap 只保证单次调用原子
    private val _oab: MutableMap<Surface, BH> = Collections.synchronizedMap(WeakHashMap<Surface, BH>())

    val oab: MutableMap<Surface, BH>
        get() = _oab

    @Volatile
    private var dummyTexId = 0x100

    val Surface.getBlackHole: Surface?
        get() = getBlackHole(this)
    val Surface.gocBlackHole: Surface
        get() = getOrCreateBlackHole(this).surface

    val Surface.gocBlackHoleTexture: SurfaceTexture
        get() = getOrCreateBlackHole(this).surfaceTexture

    val originSurfaces: List<Surface>
        get() = synchronized(_oab) { _oab.keys.filter { it != null && it.isValid } }


    private val Surface.hashCode: Int
        get() = System.identityHashCode(this)

    private val Surface.isValid: Boolean
        get() = this.isValid

    fun clear() {
        dummyTexId = 0x100
        synchronized(_oab) {
            _oab.forEach { (origin, bh) ->
                // 同步移除原生渲染目标，避免原生引擎继续往已 release 的 Surface 上渲染
                runCatching { NativeBridge.removeRenderTarget(origin) }
                bh?.release()
            }
            _oab.clear()
        }
    }

    private fun createBlackHole(): BH {
        val st = SurfaceTexture(dummyTexId)
            .apply {
                setDefaultBufferSize(320, 240)
                detachFromGLContext()
            }
        dummyTexId++
        return BH(Surface(st), st)
    }

    private fun getOrCreateBlackHole(origin: Surface): BH {
        // getOrPut 的 get+put 两步需要原子性，synchronizedMap 只保证单次调用
        return synchronized(_oab) { _oab.getOrPut(origin) { createBlackHole() } }
    }

    private fun getBlackHole(origin: Surface): Surface? {
        return _oab[origin]?.surface
    }
}
