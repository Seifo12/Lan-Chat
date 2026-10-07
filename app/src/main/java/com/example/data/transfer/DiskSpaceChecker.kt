package com.example.data.transfer

import android.content.Context
import android.os.StatFs

/** Indirection so the policy's space rule is testable without a real volume. */
interface DiskSpaceChecker {
    fun availableBytes(): Long
}

class StatFsDiskSpaceChecker(private val context: Context) : DiskSpaceChecker {
    override fun availableBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBytes
    }
}
