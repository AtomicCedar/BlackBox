package top.niunaijun.blackbox.bean

import android.graphics.drawable.Drawable

/**
 * “So 注入”页面的条目：对该应用注入自定义 so，分身启动时自动加载调用
 */
data class SoInjectAppInfo(
        val name: String,
        val packageName: String,
        var enabled: Boolean,
        val soCount: Int,
        val icon: Drawable
)
