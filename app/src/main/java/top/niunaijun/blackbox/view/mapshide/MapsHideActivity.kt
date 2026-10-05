package top.niunaijun.blackbox.view.mapshide

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.CompoundButton
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.bean.MapsHideAppInfo
import top.niunaijun.blackbox.core.MapsHideConfig
import top.niunaijun.blackbox.ui.databinding.ActivityAppConfigBinding
import top.niunaijun.blackbox.util.inflate
import top.niunaijun.blackbox.util.toast
import top.niunaijun.blackbox.view.base.LoadingActivity

/**
 * 按应用启用 /proc/self/maps 扩展规则：点击应用弹规则编辑框，勾选启用后该应用
 * 启动时对 maps 行做删行/改行伪装（规则文本落在 blackbox/hotfix/maps_hide/
 * u<userId>/<packageName>，每行一条：r xxx 删行 / m old?new 改行）。
 * 引擎 so（libpine/libblackbox）始终隐藏，不在此配置范围内。
 */
class MapsHideActivity : LoadingActivity() {

    private val viewBinding: ActivityAppConfigBinding by inflate()

    private lateinit var viewModel: MapsHideViewModel

    private lateinit var mAdapter: RVAdapter<MapsHideAppInfo>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        viewBinding.hint.setText(R.string.maps_hide_desc)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.maps_hide, true)

        viewModel = ViewModelProvider(this).get(MapsHideViewModel::class.java)

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
        }
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<MapsHideAppInfo>(this, MapsHideAdapter()).bind(viewBinding.recyclerView)
                .setItemClickListener { _, item, _ ->
                    showRuleDialog(item)
                }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
        viewBinding.stateView.showEmpty()
    }

    /**
     * 规则编辑对话框：开关控制启用/禁用，EditText 填写规则文本（预填当前内容）。
     */
    private fun showRuleDialog(item: MapsHideAppInfo) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_maps_hide, null)
        val enableSwitch = dialogView.findViewById<CompoundButton>(R.id.enable_switch)
        val rulesEdit = dialogView.findViewById<EditText>(R.id.rules_edit)
        enableSwitch.isChecked = item.enabled
        rulesEdit.setText(MapsHideConfig.getRulesText(0, item.packageName))
        MaterialDialog(this).show {
            title(text = item.name)
            customView(view = dialogView, scrollable = true)
            positiveButton(res = R.string.done) {
                val enabled = enableSwitch.isChecked
                viewModel.save(item, enabled, rulesEdit.text.toString())
                toast(if (enabled) R.string.maps_hide_on_toast else R.string.maps_hide_off_toast)
            }
            negativeButton(res = R.string.cancel)
        }
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
            val intent = Intent(context, MapsHideActivity::class.java)
            context.startActivity(intent)
        }
    }
}
