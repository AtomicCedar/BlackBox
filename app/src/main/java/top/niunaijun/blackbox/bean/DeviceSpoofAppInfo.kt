package top.niunaijun.blackbox.bean

import android.graphics.drawable.Drawable

/**
 * “设备伪装”页面的条目：对该应用伪装设备参数 / SIM / 唯一标识，分身启动时生效
 */
data class DeviceSpoofAppInfo(
        val name: String,
        val packageName: String,
        var enabled: Boolean,
        val fieldCount: Int,
        val icon: Drawable
)
