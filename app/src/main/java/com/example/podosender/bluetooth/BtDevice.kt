package com.example.podosender.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 화면에 보여줄 블루투스 기기. */
data class BtDevice(
    val address: String,
    val name: String?,
    val bonded: Boolean,
) {
    val hasName: Boolean get() = !name.isNullOrBlank()
    val displayName: String get() = if (hasName) name!! else "(이름 없음)"
}

object BtPermissions {
    /** Android 12(API 31)+ 는 SCAN/CONNECT, 그 이하는 위치 권한이 검색에 필요합니다. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasAll(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
