package top.niunaijun.blackbox.view.devicespoof

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.bean.DeviceSpoofAppInfo
import top.niunaijun.blackbox.ui.databinding.ItemAppConfigBinding


class DeviceSpoofAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return DeviceSpoofVH(inflate(R.layout.item_app_config, parent))
    }

    class DeviceSpoofVH(itemView: View) : RVHolder<DeviceSpoofAppInfo>(itemView) {

        private val binding = ItemAppConfigBinding.bind(itemView)

        override fun setContent(item: DeviceSpoofAppInfo, isSelected: Boolean, payload: Any?) {
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

        private fun statusText(item: DeviceSpoofAppInfo): String {
            val context = itemView.context
            return when {
                !item.enabled -> context.getString(R.string.device_spoof_status_disabled)
                item.fieldCount > 0 -> context.getString(R.string.device_spoof_status_enabled, item.fieldCount)
                else -> context.getString(R.string.device_spoof_status_enabled_empty)
            }
        }
    }
}
