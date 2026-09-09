package com.nothing.camera2magic.hook

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import androidx.annotation.OptIn

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM

class Camera3 {

    companion object {
        private const val TAG = "[Camera3]"

        // 图片媒体解码预算：长边上限（横竖对称）。4K 是下游有用分辨率的天花板
        // （native 缩放到输出端，输出最大 4K 录制），3840 同时保证低于最低保障的
        // GL_MAX_TEXTURE_SIZE(4096)，避免 lockHardwareCanvas 超限静默失败变黑帧
        private const val FRAME_LONG_EDGE = 3840
        private const val FRAME_LONG_EDGE_LOW_RAM = 1920

        // 帧重绘间隔 ≈30fps：仅驱动 OES 纹理持续更新，实际输出帧率由相机管线决定
        private const val FRAME_INTERVAL_MS = 33L

        // Hook 跑在目标应用进程，内存账算它的：低内存设备降档
        private val frameLongEdge: Int by lazy {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.isLowRamDevice == true
            }.getOrDefault(false).let { if (it) FRAME_LONG_EDGE_LOW_RAM else FRAME_LONG_EDGE }
        }

        private val camera3Handler = Camera3Extended.handler

        private val context: Context get() = GlobalState.appContext

        @Volatile
        private var initialized = AtomicBoolean(false)
        private var player: ExoPlayer? = null
        private var pfd: ParcelFileDescriptor? = null
        private var imageRendering: Boolean = false
        private var cachedBitmap: Bitmap? = null
        private var oesTextureId: Int = 0
        private var surface: Surface? = null
        private var surfaceTexture: SurfaceTexture? = null
    }

    enum class State { IDLE, BUFFERING, READY, ENDED, PLAYING, PAUSE, ERROR }
    var onPlayerStateChangeListener: ((state: State) -> Unit)? = null

    private val playerListener = object : Player.Listener {

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val pixelRatio = videoSize.pixelWidthHeightRatio
            val width = (videoSize.width * pixelRatio).toInt()
            val height = videoSize.height
            val rotation = videoSize.unappliedRotationDegrees
            NB.updateFrameInfo(width, height, rotation)
            SM.applyManualRotationToNative()
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when(playbackState) {
                Player.STATE_IDLE -> notifyState(State.IDLE)
                Player.STATE_BUFFERING -> notifyState(State.BUFFERING)
                Player.STATE_READY -> notifyState(State.READY)
                Player.STATE_ENDED -> notifyState(State.ENDED)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) notifyState(State.PLAYING)
            else if (player?.playbackState != Player.STATE_ENDED) notifyState(State.PAUSE)
        }

        override fun onPlayerError(error: PlaybackException) {
            Dog.e(TAG, "${error.errorCodeName} - ${error.message}", error, SM.enableLog)
            notifyState(State.ERROR)
        }
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        oesTextureId = NB.createOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(16, 16)
            setOnFrameAvailableListener({ _ ->
                NB.notifyFrameAvailable()
            }, camera3Handler)
        }

        NB.setSurfaceTexture(surfaceTexture!!)
        surface = Surface(surfaceTexture)

        player = ExoPlayer.Builder(GlobalState.appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(playerListener)
        }
        Dog.i(TAG, "camera3 client initialized.", SM.enableLog)
    }

    fun start(magic: MagicHook, validMedia: ValidMedia) {
        camera3Handler.post {
            init()
            val (name, type) = validMedia
            // 旧 fd 先关再赋新值：连续两次 start 之间没有 stop 时旧 fd 会泄漏。
            // DataSource 在 open 时已 dup 私有副本，关它不影响仍在读的旧播放
            runCatching { pfd?.close() }
            pfd = null
            when (type) {
                MagicType.LOCAL_VIDEO  -> {
                    pfd = magic.openRemoteFile(name)
                    pfd?.let { handleLocalVideo(it) }
                }

                MagicType.LOCAL_IMAGE -> {
                    pfd = magic.openRemoteFile(name)
                    pfd?.let { handleLocalImage(it) }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun handleLocalVideo(pfd: ParcelFileDescriptor) {
        val volumeValue = if (SM.playSound) 1f else 0f
        val factory = DataSource.Factory { MagicDataSource(pfd) }
        val mediaSourceFactory = DefaultMediaSourceFactory(factory)
        val mediaItem = MediaItem.fromUri("LOCAL://VIDEO")
        camera3Handler.post {
            player?.apply {
                volume = volumeValue
                setVideoSurface(surface)
                setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                prepare()
                playWhenReady = true
            }
        }
    }

    private fun handleLocalImage(pfd: ParcelFileDescriptor) {

        runCatching {
            val fd = pfd.fileDescriptor

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFileDescriptor(fd, null, options)

            try {
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
            } catch (e: Exception) {
                Dog.e(TAG, "Failed to seek file descriptor", e, SM.enableLog)
            }

            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = calculateInSampleSize(options, frameLongEdge)


            val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options)
                ?: throw IllegalStateException("decode image failed.")

            NB.updateFrameInfo(bitmap.width, bitmap.height, 0)
            SM.applyManualRotationToNative()
            surfaceTexture?.setDefaultBufferSize(bitmap.width, bitmap.height)
            cachedBitmap = bitmap
            imageRendering = true
            camera3Handler.post(imageRenderRunnable)
        }.onFailure { e ->
            Dog.e(TAG, "${e.message}", e, SM.enableLog)
        }
    }

    private val imageRenderRunnable = object : Runnable {
        override fun run() {
            if (!initialized.get() || !imageRendering) return
            drawBitmapToSurface()
            if (imageRendering) camera3Handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun drawBitmapToSurface() {
        val bitmap = cachedBitmap ?: return
        runCatching {
            val canvas = surface?.lockHardwareCanvas()// minSDK 26
            canvas?.let {
                it.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                it.drawBitmap(bitmap, 0f, 0f, null)
            }
            surface?.unlockCanvasAndPost(canvas)
        }
    }
    fun pause () {
        camera3Handler.post { player?.playWhenReady = false }
    }
    fun seekTo(position: Long) { // Ms
        camera3Handler.post { player?.seekTo(position) }
    }
    fun stop() {
        if (!initialized.get()) return
        camera3Handler.post {
            imageRendering = false
            camera3Handler.removeCallbacks(imageRenderRunnable)
            player?.release()
            releaseResources()
            initialized.set(false)
        }
    }

    fun releaseResources() {
        if (cachedBitmap != null) {
            val tmp = cachedBitmap
            cachedBitmap = null
            tmp?.recycle()
        }
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        oesTextureId = 0
        pfd?.close()
        pfd = null
    }

    private fun notifyState(state: State) {
        onPlayerStateChangeListener?.invoke(state)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, maxLongEdge: Int): Int {
        // 旧实现按 reqWidth/reqHeight 双边收紧且循环条件要求两个半边都 >= 预算，
        // 只有竖图真正受限：横图（高 < 2×reqHeight）一律 inSampleSize=1 全尺寸解码
        // （4000×3000 = 48MB）。改为按长边对称收紧，语义「不超过」：pow2 粒度最坏
        // 落到预算一半，但绝不超限、绝不放大小图
        val longEdge = maxOf(options.outWidth, options.outHeight)
        var inSampleSize = 1
        while (longEdge / inSampleSize > maxLongEdge) inSampleSize *= 2
        return inSampleSize
    }

}
