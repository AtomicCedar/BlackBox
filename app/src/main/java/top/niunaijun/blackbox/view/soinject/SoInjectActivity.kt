package top.niunaijun.blackbox.view.soinject

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.bean.SoInjectAppInfo
import top.niunaijun.blackbox.core.SoInjectConfig
import top.niunaijun.blackbox.ui.databinding.ActivityAppConfigBinding
import top.niunaijun.blackbox.ui.databinding.DialogSoInjectBinding
import top.niunaijun.blackbox.ui.databinding.ItemSoFileBinding
import top.niunaijun.blackbox.util.inflate
import top.niunaijun.blackbox.util.toast
import top.niunaijun.blackbox.view.base.LoadingActivity

/**
 * 按应用注入 so：点击应用弹配置框，选择 so 文件复制进分身体 lib 目录，
 * 分身启动时自动加载（构造函数与 JNI_OnLoad 随加载执行）。
 */
class SoInjectActivity : LoadingActivity() {

    private val viewBinding: ActivityAppConfigBinding by inflate()

    private lateinit var viewModel: SoInjectViewModel

    private lateinit var mAdapter: RVAdapter<SoInjectAppInfo>

    private var currentItem: SoInjectAppInfo? = null

    private var dialogBinding: DialogSoInjectBinding? = null

    private val soPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val item = currentItem
            if (uris.isNotEmpty() && item != null) {
                for (uri in uris) {
                    viewModel.addSo(item, uri)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        viewBinding.hint.setText(R.string.so_inject_desc)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.so_inject, true)

        viewModel = ViewModelProvider(this).get(SoInjectViewModel::class.java)

        initRecyclerView()
    }

    private fun observeLiveData() {
        viewBinding.stateView.showLoading()
        viewModel.appsLiveData.observe(this) {
            if (it.isNullOrEmpty()) {
                viewBinding.stateView.showEmpty()
            } else {
                mAdapter.setItems(it)
                viewBinding.stateView.showContent()
            }
            // 主列表刷新时同步刷新对话框内的 so 列表（添加/删除/开关后条目即时消失或出现）
            refreshDialogSoList()
        }
        viewModel.resultLiveData.observe(this) {
            if (it == null) {
                toast(R.string.so_inject_added_toast)
                refreshDialogSoList()
            } else {
                toast(getString(R.string.so_inject_add_fail, it))
            }
        }
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<SoInjectAppInfo>(this, SoInjectAdapter { item ->
            val enabled = !item.enabled
            viewModel.setEnabled(item, enabled)
            toast(if (enabled) R.string.so_inject_on_toast else R.string.so_inject_off_toast)
        }).bind(viewBinding.recyclerView)
                .setItemClickListener { _, item, _ ->
                    showDialog(item)
                }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
        viewBinding.stateView.showEmpty()
    }

    /**
     * 配置对话框：开关控制启用/禁用，so 列表可删除，"添加 so"走文件选择器。
     */
    private fun showDialog(item: SoInjectAppInfo) {
        currentItem = item
        val binding = DialogSoInjectBinding.inflate(LayoutInflater.from(this))
        dialogBinding = binding
        binding.enableSwitch.isChecked = item.enabled
        binding.soList.layoutManager = LinearLayoutManager(this)
        val soAdapter = RVAdapter<String>(this, SoFileAdapter { soName ->
            viewModel.removeSo(item, soName)
        }).bind(binding.soList)
        refreshDialogSoList(soAdapter)
        binding.addSo.setOnClickListener {
            // SAF 只能按 mime 过滤：.so 一般归类为 octet-stream，配合 x-sharedlib，
            // 图片/视频/文档等无关类型都不会出现在选择器里
            soPicker.launch(arrayOf("application/octet-stream", "application/x-sharedlib"))
        }
        MaterialDialog(this).show {
            title(text = item.name)
            customView(view = binding.root, scrollable = true)
            positiveButton(res = R.string.done) {
                val enabled = binding.enableSwitch.isChecked
                if (enabled != item.enabled) {
                    viewModel.setEnabled(item, enabled)
                    toast(if (enabled) R.string.so_inject_on_toast else R.string.so_inject_off_toast)
                }
            }
            negativeButton(res = R.string.cancel)
        }
    }

    private fun refreshDialogSoList() {
        dialogBinding?.let { binding ->
            val adapter = binding.soList.adapter as? RVAdapter<*> ?: return
            refreshDialogSoList(adapter as RVAdapter<String>)
        }
    }

    private fun refreshDialogSoList(soAdapter: RVAdapter<String>) {
        val item = currentItem ?: return
        soAdapter.setItems(SoInjectConfig.getSoFiles(0, item.packageName))
    }

    override fun onStart() {
        super.onStart()
        observeLiveData()
        viewModel.getApps()
    }

    override fun onStop() {
        super.onStop()
        viewModel.appsLiveData.value = null
        viewModel.appsLiveData.removeObservers(this)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SoInjectActivity::class.java)
            context.startActivity(intent)
        }
    }

    private class SoFileAdapter(private val onRemove: (String) -> Unit) : RVHolderFactory() {

        override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
            return SoFileVH(inflate(R.layout.item_so_file, parent), onRemove)
        }

        class SoFileVH(itemView: View, private val onRemove: (String) -> Unit) : RVHolder<String>(itemView) {

            private val binding = ItemSoFileBinding.bind(itemView)

            override fun setContent(item: String, isSelected: Boolean, payload: Any?) {
                binding.soName.text = item
                binding.remove.setOnClickListener {
                    onRemove(item)
                }
            }
        }
    }
}
