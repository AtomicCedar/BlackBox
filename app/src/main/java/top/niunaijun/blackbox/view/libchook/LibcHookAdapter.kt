package top.niunaijun.blackbox.view.libchook

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.bean.LibcHookAppInfo
import top.niunaijun.blackbox.ui.databinding.ItemAppConfigBinding


class LibcHookAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return LibcHookVH(inflate(R.layout.item_app_config, parent))
    }

    class LibcHookVH(itemView: View) : RVHolder<LibcHookAppInfo>(itemView) {

        private val binding = ItemAppConfigBinding.bind(itemView)

        override fun setContent(item: LibcHookAppInfo, isSelected: Boolean, payload: Any?) {
            binding.icon.setImageDrawable(item.icon)
            binding.name.text = item.name
            binding.desc.text = item.packageName
            binding.status.text = itemView.context.getString(
                    if (item.disabled) R.string.libc_hook_status_disabled else R.string.libc_hook_status_enabled)
            binding.status.setTextColor(ContextCompat.getColor(itemView.context,
                    if (item.disabled) R.color.maps_hide_enabled else R.color.secondary_text))
            binding.enable.isChecked = item.disabled
            binding.enable.setOnCheckedChangeListener { buttonView, _ ->
                if (buttonView.isPressed) {
                    binding.root.performClick()
                }
            }
        }
    }
}
