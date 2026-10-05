package top.niunaijun.blackbox.view.mapshide

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.bean.MapsHideAppInfo
import top.niunaijun.blackbox.ui.databinding.ItemAppConfigBinding


class MapsHideAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return MapsHideVH(inflate(R.layout.item_app_config, parent))
    }

    class MapsHideVH(itemView: View) : RVHolder<MapsHideAppInfo>(itemView) {

        private val binding = ItemAppConfigBinding.bind(itemView)

        override fun setContent(item: MapsHideAppInfo, isSelected: Boolean, payload: Any?) {
            binding.icon.setImageDrawable(item.icon)
            binding.name.text = item.name
            binding.desc.text = item.packageName
            binding.status.text = statusText(item)
            binding.status.setTextColor(ContextCompat.getColor(itemView.context,
                    if (item.enabled) R.color.maps_hide_enabled else R.color.secondary_text))
            binding.enable.isChecked = item.enabled
            binding.enable.setOnCheckedChangeListener { buttonView, _ ->
                if (buttonView.isPressed) {
                    binding.root.performClick()
                }
            }
        }

        private fun statusText(item: MapsHideAppInfo): String {
            val context = itemView.context
            return when {
                !item.enabled -> context.getString(R.string.maps_hide_status_disabled)
                item.ruleCount > 0 -> context.getString(R.string.maps_hide_status_enabled, item.ruleCount)
                else -> context.getString(R.string.maps_hide_status_enabled_empty)
            }
        }
    }
}
