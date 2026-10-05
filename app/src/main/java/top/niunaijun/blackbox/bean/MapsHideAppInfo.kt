package top.niunaijun.blackbox.bean

import android.graphics.drawable.Drawable

/**
 * “Maps 隐藏”页面的条目：勾选后对该应用启用 /proc/self/maps 扩展规则（删行/改行伪装）
 */
data class MapsHideAppInfo(
        val name: String,
        val packageName: String,
        var enabled: Boolean,
        val ruleCount: Int,
        val icon: Drawable
)
