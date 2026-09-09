package com.nothing.camera2magic.hook

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.FileInputStream

@OptIn(UnstableApi::class)
class MagicDataSource(private val pfd: ParcelFileDescriptor) : BaseDataSource(/* isNetwork = */ false) {

    private var fis: FileInputStream? = null
    private var opened = false
    private var bytesRemaining = 0L

    // open() 时 dup 出的私有 fd 副本：并发 open 各自 lseek 各自的偏移互不踩位，
    // close() 只关副本，原始 fd 的所有权留在 Camera3.releaseResources()
    private var ownedPfd: ParcelFileDescriptor? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        // 防御：上一次 open 未正常 close 时先回收旧副本，避免 dup 泄漏
        runCatching { ownedPfd?.close() }
        ownedPfd = null
        fis = null

        try {
            val owned = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            ownedPfd = owned
            Os.lseek(owned.fileDescriptor, dataSpec.position, OsConstants.SEEK_SET)
            fis = FileInputStream(owned.fileDescriptor)

            val totalLength = owned.statSize
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                totalLength - dataSpec.position
            }

            if (bytesRemaining < 0) throw java.io.EOFException("Seek position is beyond file size")

            opened = true

            transferStarted(dataSpec)
            return bytesRemaining

        } catch (e: Exception) {
            // dup 成功但后续失败的场合，把私有副本也关掉，不留半开 fd
            runCatching { ownedPfd?.close() }
            ownedPfd = null
            fis = null
            throw java.io.IOException("Failed to open FD at position ${dataSpec.position}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            fis?.read(buffer, offset, bytesToRead) ?: -1
        } catch (e: java.io.IOException) {
            throw e
        }

        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRemaining -= read

        bytesTransferred(read)
        return read
    }

    override fun getUri() = null

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        fis = null
        runCatching { ownedPfd?.close() }
        ownedPfd = null
    }
}