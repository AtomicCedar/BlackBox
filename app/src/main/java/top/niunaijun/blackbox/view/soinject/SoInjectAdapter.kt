package top.niunaijun.blackbox.view.soinject

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.bean.SoInjectAppInfo
import top.niunaijun.blackbox.ui.databinding.ItemAppConfigBinding


class SoInjectAdapter(private val onToggle: (SoInjectAppInfo) -> Unit) : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return SoInjectVH(inflate(R.layout.item_app_config, parent), onToggle)
    }

    class SoInjectVH(itemView: View, private val onToggle: (SoInjectAppInfo) -> Unit) :
            RVHolder<SoInjectAppInfo>(itemView) {

        private val binding = ItemAppConfigBinding.bind(itemView)

        override fun setContent(item: SoInjectAppInfo, isSelected: Boolean, payload: Any?) {
            binding.icon.setImageDrawable(item.icon)
            binding.name.text = item.name
            binding.desc.text = item.packageName
            binding.status.text = statusText(item)
            binding.status.setTextColor(ContextCompat.getColor(itemView.context,
                    if (item.enabled) R.color.maps_hide_enabled else R.color.secondary_text))
            binding.enable.isChecked = item.enabled
            binding.enable.setOnCheckedChangeListener { buttonView, _ ->
                if (buttonView.isPressed) {
                    // 列表开关直接切换启用状态（与"禁用libc hook"一致），
                    // 条目其他区域点击才打开配置对话框
                    onToggle(item)
                }
            }
        }

        private fun statusText(item: SoInjectAppInfo): String {
            val context = itemView.context
            return when {
                !item.enabled -> context.getString(R.string.so_inject_status_disabled)
                item.soCount > 0 -> context.getString(R.string.so_inject_status_enabled, item.soCount)
                else -> context.getString(R.string.so_inject_status_enabled_empty)
            }
        }
    }
}
