package top.niunaijun.blackbox.view.mapshide

import android.content.pm.PackageManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.bean.MapsHideAppInfo
import top.niunaijun.blackbox.core.MapsHideConfig
import top.niunaijun.blackbox.fake.frameworks.BXposedManager

class MapsHideViewModel : ViewModel() {

    val appsLiveData = MutableLiveData<List<MapsHideAppInfo>>()

    fun getApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packageManager = BlackBoxCore.getPackageManager()
            val apps = BlackBoxCore.get().getInstalledApplications(PackageManager.GET_META_DATA, 0)
                    .filterNot { it.packageName == BlackBoxCore.getHostPkg() }
            val result = apps.map {
                MapsHideAppInfo(
                        it.loadLabel(packageManager).toString(),
                        it.packageName,
                        MapsHideConfig.isEnabled(0, it.packageName),
                        MapsHideConfig.getRules(0, it.packageName).size,
                        it.loadIcon(packageManager)
                )
            }.sortedBy { it.name }
            appsLiveData.postValue(result)
        }
    }

    /**
     * 保存该应用的 maps 规则：enabled 决定启用/禁用，规则文本原样落盘。
     * maps 规则在 bindApplication 阶段注册，杀掉后下次启动生效。
     */
    fun save(info: MapsHideAppInfo, enabled: Boolean, rulesText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MapsHideConfig.setRules(0, info.packageName, enabled, rulesText)
            BXposedManager.get().killPackageAsUser(info.packageName, 0)
            val ruleCount = if (enabled) MapsHideConfig.getRules(0, info.packageName).size else 0
            appsLiveData.postValue(appsLiveData.value?.map {
                if (it.packageName == info.packageName) {
                    it.copy(enabled = enabled, ruleCount = ruleCount)
                } else {
                    it
                }
            })
        }
    }
}
