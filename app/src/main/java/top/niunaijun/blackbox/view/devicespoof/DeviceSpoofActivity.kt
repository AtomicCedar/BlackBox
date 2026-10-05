package top.niunaijun.blackbox.view.devicespoof

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.list.listItems
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.bean.DeviceSpoofAppInfo
import top.niunaijun.blackbox.core.DeviceSpoofConfig
import top.niunaijun.blackbox.ui.databinding.ActivityAppConfigBinding
import top.niunaijun.blackbox.ui.databinding.DialogDeviceSpoofBinding
import top.niunaijun.blackbox.util.inflate
import top.niunaijun.blackbox.util.toast
import top.niunaijun.blackbox.view.base.LoadingActivity
import java.util.Locale
import kotlin.random.Random

/**
 * 按应用设备伪装（机器模拟）：点击应用弹配置框，可一键随机整机与 ID、从机型库
 * 选品牌/型号、或手填各字段；分身启动时改写 Build 字段与 Settings/TelephonyManager
 * 读数。配置落在 blackbox/hotfix/device_spoof/u<userId>/<packageName>。
 */
class DeviceSpoofActivity : LoadingActivity() {

    private val viewBinding: ActivityAppConfigBinding by inflate()

    private lateinit var viewModel: DeviceSpoofViewModel

    private lateinit var mAdapter: RVAdapter<DeviceSpoofAppInfo>

    private var currentItem: DeviceSpoofAppInfo? = null

    private var dialogBinding: DialogDeviceSpoofBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        viewBinding.hint.setText(R.string.device_spoof_desc)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.device_spoof, true)

        viewModel = ViewModelProvider(this).get(DeviceSpoofViewModel::class.java)

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
        mAdapter = RVAdapter<DeviceSpoofAppInfo>(this, DeviceSpoofAdapter()).bind(viewBinding.recyclerView)
                .setItemClickListener { _, item, _ ->
                    showEditDialog(item)
                }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
        viewBinding.stateView.showEmpty()
    }

    /**
     * 配置对话框：开关控制启用/禁用；一键随机整机与 ID；品牌/型号两级选择（机型库）；
     * 设备参数 / SIM / 唯一标识三段字段。
     */
    private fun showEditDialog(item: DeviceSpoofAppInfo) {
        currentItem = item
        val binding = DialogDeviceSpoofBinding.inflate(LayoutInflater.from(this))
        dialogBinding = binding
        val config = DeviceSpoofConfig.getConfig(0, item.packageName)

        binding.enableSwitch.isChecked = item.enabled
        binding.fieldManufacturer.setText(config.manufacturer)
        binding.fieldMarketName.setText(config.marketName)
        binding.fieldModel.setText(config.model)
        binding.fieldProduct.setText(config.product)
        binding.fieldDevice.setText(config.device)
        binding.fieldBoard.setText(config.board)
        binding.fieldFingerprint.setText(config.fingerprint)
        binding.fieldBuildId.setText(config.buildId)
        if (config.sdkInt > 0) {
            binding.fieldSdkInt.setText(config.sdkInt.toString())
        }
        binding.fieldRelease.setText(config.release)
        binding.fieldRadioVersion.setText(config.radioVersion)
        binding.fieldCharacteristics.setText(config.characteristics)
        binding.fieldSimOperator.setText(config.simOperator)
        binding.fieldSimOperatorName.setText(config.simOperatorName)
        binding.fieldSimCountryIso.setText(config.simCountryIso)
        binding.fieldDeviceId.setText(config.deviceId)
        binding.fieldImei.setText(config.imei)
        binding.fieldPhoneNumber.setText(config.phoneNumber)
        refreshBrandModelRows(binding, config.manufacturer, config.model)

        binding.randomBtn.setOnClickListener {
            randomize(binding)
        }
        binding.clearBtn.setOnClickListener {
            clearFields(binding)
        }
        binding.brandRow.setOnClickListener {
            showBrandPicker(binding)
        }
        binding.modelRow.setOnClickListener {
            showModelPicker(binding)
        }

        MaterialDialog(this).show {
            title(text = item.name)
            customView(view = binding.root, scrollable = true)
            positiveButton(res = R.string.done) {
                val enabled = binding.enableSwitch.isChecked
                viewModel.save(item, enabled, collectConfig(binding))
                toast(if (enabled) R.string.device_spoof_on_toast else R.string.device_spoof_off_toast)
            }
            negativeButton(res = R.string.cancel)
        }
    }

    /**
     * 一键随机：机型库随机一款机型，连带推导 product/board/指纹/系统版本等设备参数，
     * SIM 随机选一个国内运营商，唯一标识（Android ID/IMEI/手机号）全部随机。
     */
    private fun randomize(binding: DialogDeviceSpoofBinding) {
        var brand = ""
        var model = ""
        var device = ""
        var marketName = ""
        DeviceDBHelper(this).use { db ->
            val d = db.randomDevice()
            if (d != null) {
                brand = d.brand ?: ""
                model = d.model ?: ""
                device = d.codeAlias ?: ""
                marketName = d.modelName ?: ""
            }
        }
        binding.fieldManufacturer.setText(brand)
        binding.fieldMarketName.setText(marketName)
        binding.fieldModel.setText(model)
        binding.fieldDevice.setText(device)
        // product 用 device 兜底、board 用 device（Guise_Reborn 同款：product 缺失时
        // 回退 device/model，保证指纹各段都来自同一台真实设备的字段）
        val product = model.ifBlank { device }
        binding.fieldProduct.setText(product)
        binding.fieldBoard.setText(device)
        // 系统版本从不低于真机 SDK_INT 的版本里随机：SDK_INT/RELEASE 自洽，且不会
        // 伪装到旧 API 导致应用走旧分支崩溃（对齐 SDK_INT 下限保护）
        val (sdk, release) = randomSdkRelease()
        if (sdk > 0) {
            binding.fieldSdkInt.setText(sdk.toString())
            binding.fieldRelease.setText(release)
            // Build.ID 前缀/年份与 Android 版本强相关（13→TP1A/2022、14→UP1A/2023），
            // 不再是乱拼的 RQ3A；指纹全段来自同一套字段值，空格/冒号做安全化
            val buildId = randomBuildId(release)
            val incremental = randomIncremental()
            binding.fieldBuildId.setText(buildId)
            binding.fieldFingerprint.setText(randomFingerprint(brand, product, device, release, buildId, incremental))
        }
        binding.fieldRadioVersion.setText(randomRadioVersion())
        binding.fieldCharacteristics.setText(randomCharacteristics())
        // SIM：随机一个国内运营商
        val carrier = CARRIERS[Random.nextInt(CARRIERS.size)]
        binding.fieldSimOperator.setText(carrier.first)
        binding.fieldSimOperatorName.setText(carrier.second)
        binding.fieldSimCountryIso.setText("cn")
        // 唯一标识
        binding.fieldDeviceId.setText(randomAndroidId())
        binding.fieldImei.setText(randomImei())
        binding.fieldPhoneNumber.setText(randomPhone())
        refreshBrandModelRows(binding, brand, model)
    }

    private val CARRIERS = arrayOf(
            "46000" to "中国移动",
            "46001" to "中国联通",
            "46011" to "中国电信"
    )

    /**
     * (API, 系统版本) 自洽的一对，且 API 不低于真机 SDK_INT。
     * 返回 (0, "") 表示没有可用的版本（真机太新），调用方保持系统版本不伪装。
     */
    private fun randomSdkRelease(): Pair<Int, String> {
        val pairs = arrayOf(
                36 to "16", 35 to "15", 34 to "14", 33 to "13",
                32 to "12", 31 to "11", 30 to "10")
        val real = Build.VERSION.SDK_INT
        val candidates = pairs.filter { it.first >= real }
        if (candidates.isEmpty()) {
            return 0 to ""
        }
        return candidates[Random.nextInt(candidates.size)]
    }

    /**
     * Build.ID 按 Android 版本生成：前缀与首年随大版本变化（Google 官方构建号规律），
     * 避免"Android 13 机型顶着 Android 12 的 RQ3A 构建号"这种明显不自洽的组合。
     */
    private fun randomBuildId(version: String): String {
        val major = version.substringBefore('.').toIntOrNull()
        val (prefix, firstYear) = when (major) {
            10 -> "QP1A" to 19
            11 -> "RP1A" to 20
            12 -> "SP1A" to 21
            13 -> "TP1A" to 22
            14 -> "UP1A" to 23
            15 -> "AP3A" to 24
            16 -> "BP2A" to 25
            17 -> "CP1A" to 26
            else -> "GU1A" to 24
        }
        return String.format(
                Locale.ROOT,
                "$prefix.%02d%02d%02d.%03d",
                Random.nextInt(firstYear, firstYear + 2),
                Random.nextInt(1, 13),
                Random.nextInt(1, 29),
                Random.nextInt(1000))
    }

    /** 指纹串：品牌/产品/设备:版本/构建号/增量:user/release-keys，各段来自同一套字段 */
    private fun randomFingerprint(
            brand: String,
            product: String,
            device: String,
            version: String,
            buildId: String,
            incremental: String
    ): String {
        val safeBrand = fingerprintSafe(brand, "generic")
        val safeDevice = fingerprintSafe(device, "device")
        val safeProduct = fingerprintSafe(product, safeDevice)
        val safeRelease = fingerprintSafe(version, "16")
        val safeBuildId = fingerprintSafe(buildId, randomBuildId(version))
        return "$safeBrand/$safeProduct/$safeDevice:$safeRelease/$safeBuildId/$incremental:user/release-keys"
    }

    /** 指纹段安全化：真实指纹不含空格/冒号/斜杠，替换为下划线 */
    private fun fingerprintSafe(value: String, fallback: String): String {
        return value.trim().takeIf { it.isNotEmpty() }
                ?.replace(Regex("[\\s/:]+"), "_")
                ?: fallback
    }

    private fun randomIncremental(): String {
        return buildString { repeat(7) { append(Random.nextInt(10)) } }
    }

    private fun randomRadioVersion(): String {
        return "M${Random.nextInt(1000)}.${Random.nextInt(100)}.${Random.nextInt(100)}"
    }

    private fun randomCharacteristics(): String {
        return arrayOf("default", "phone", "nosdcard")[Random.nextInt(3)]
    }

    /** 一键清空：所有伪装字段归空（品牌/型号行复位），保存后即不伪装 */
    private fun clearFields(binding: DialogDeviceSpoofBinding) {
        binding.fieldManufacturer.setText("")
        binding.fieldMarketName.setText("")
        binding.fieldModel.setText("")
        binding.fieldProduct.setText("")
        binding.fieldDevice.setText("")
        binding.fieldBoard.setText("")
        binding.fieldFingerprint.setText("")
        binding.fieldBuildId.setText("")
        binding.fieldSdkInt.setText("")
        binding.fieldRelease.setText("")
        binding.fieldRadioVersion.setText("")
        binding.fieldCharacteristics.setText("")
        binding.fieldSimOperator.setText("")
        binding.fieldSimOperatorName.setText("")
        binding.fieldSimCountryIso.setText("")
        binding.fieldDeviceId.setText("")
        binding.fieldImei.setText("")
        binding.fieldPhoneNumber.setText("")
        refreshBrandModelRows(binding, "", "")
    }

    private fun showBrandPicker(binding: DialogDeviceSpoofBinding) {
        val brands = DeviceDBHelper(this).use { it.getAllBrand() }
        if (brands.isEmpty()) {
            return
        }
        val labels = brands.values.toList()
        val keys = brands.keys.toList()
        MaterialDialog(this).show {
            title(res = R.string.device_spoof_brand)
            listItems(items = labels) { _, index, _ ->
                val brandKey = keys[index]
                binding.fieldManufacturer.setText(brandKey)
                binding.fieldModel.setText("")
                binding.fieldDevice.setText("")
                refreshBrandModelRows(binding, brandKey, "")
            }
        }
    }

    private fun showModelPicker(binding: DialogDeviceSpoofBinding) {
        val brand = binding.fieldManufacturer.text.toString()
        if (brand.isEmpty()) {
            toast(R.string.device_spoof_pick_brand_first)
            return
        }
        val devices = DeviceDBHelper(this).use { db ->
            db.getDevicesByBrand(brand).filterNot { it.modelName.isNullOrBlank() || it.model.isNullOrBlank() }
        }
        if (devices.isEmpty()) {
            return
        }
        MaterialDialog(this).show {
            title(res = R.string.device_spoof_model)
            listItems(items = devices.map {
                if (it.verName == "#" || it.verName == null) it.modelName!!
                else "${it.modelName!!} (${it.verName.removePrefix("#")})"
            }) { _, index, _ ->
                val device = devices[index]
                binding.fieldModel.setText(device.model)
                binding.fieldMarketName.setText(device.modelName)
                binding.fieldDevice.setText(device.codeAlias ?: "")
                refreshBrandModelRows(binding, brand, device.model)
            }
        }
    }

    /** 品牌/型号两行显示当前值（空时只显示行名） */
    private fun refreshBrandModelRows(binding: DialogDeviceSpoofBinding, brand: String?, model: String?) {
        binding.brandRow.text = if (brand.isNullOrEmpty()) getString(R.string.device_spoof_brand)
        else getString(R.string.device_spoof_brand_value, brand)
        binding.modelRow.text = if (model.isNullOrEmpty()) getString(R.string.device_spoof_model)
        else getString(R.string.device_spoof_model_value, model)
    }

    private fun collectConfig(binding: DialogDeviceSpoofBinding): DeviceSpoofConfig.Data {
        val data = DeviceSpoofConfig.Data()
        data.manufacturer = binding.fieldManufacturer.text.toString().trim()
        data.marketName = binding.fieldMarketName.text.toString().trim()
        data.model = binding.fieldModel.text.toString().trim()
        data.product = binding.fieldProduct.text.toString().trim()
        data.device = binding.fieldDevice.text.toString().trim()
        data.board = binding.fieldBoard.text.toString().trim()
        data.fingerprint = binding.fieldFingerprint.text.toString().trim()
        data.buildId = binding.fieldBuildId.text.toString().trim()
        data.sdkInt = parsePositiveInt(binding.fieldSdkInt)
        data.release = binding.fieldRelease.text.toString().trim()
        data.radioVersion = binding.fieldRadioVersion.text.toString().trim()
        data.characteristics = binding.fieldCharacteristics.text.toString().trim()
        data.simOperator = binding.fieldSimOperator.text.toString().trim()
        data.simOperatorName = binding.fieldSimOperatorName.text.toString().trim()
        data.simCountryIso = binding.fieldSimCountryIso.text.toString().trim()
        data.deviceId = binding.fieldDeviceId.text.toString().trim()
        data.imei = binding.fieldImei.text.toString().trim()
        data.phoneNumber = binding.fieldPhoneNumber.text.toString().trim()
        return data
    }

    private fun parsePositiveInt(editText: EditText): Int {
        return editText.text.toString().trim().toIntOrNull() ?: -1
    }

    private fun randomAndroidId(): String {
        val chars = "0123456789abcdef"
        return buildString { repeat(16) { append(chars[Random.nextInt(chars.length)]) } }
    }

    private fun randomImei(): String {
        return buildString {
            append(Random.nextInt(1, 10))
            repeat(14) { append(Random.nextInt(10)) }
        }
    }

    private fun randomPhone(): String {
        return buildString {
            append("1")
            repeat(10) { append(Random.nextInt(10)) }
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
            val intent = Intent(context, DeviceSpoofActivity::class.java)
            context.startActivity(intent)
        }
    }
}
