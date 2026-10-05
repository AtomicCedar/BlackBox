package top.niunaijun.blackbox.view.devicespoof

import android.content.pm.PackageManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.bean.DeviceSpoofAppInfo
import top.niunaijun.blackbox.core.DeviceSpoofConfig
import top.niunaijun.blackbox.fake.frameworks.BXposedManager

class DeviceSpoofViewModel : ViewModel() {

    val appsLiveData = MutableLiveData<List<DeviceSpoofAppInfo>>()

    fun getApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packageManager = BlackBoxCore.getPackageManager()
            val apps = BlackBoxCore.get().getInstalledApplications(PackageManager.GET_META_DATA, 0)
                    .filterNot { it.packageName == BlackBoxCore.getHostPkg() }
            val result = apps.map {
                DeviceSpoofAppInfo(
                        it.loadLabel(packageManager).toString(),
                        it.packageName,
                        DeviceSpoofConfig.isEnabled(0, it.packageName),
                        DeviceSpoofConfig.getConfig(0, it.packageName).fieldCount(),
                        it.loadIcon(packageManager)
                )
            }.sortedBy { it.name }
            appsLiveData.postValue(result)
        }
    }

    /**
     * 保存配置：enabled 决定启用/禁用，配置值原样落盘。变更后结束该应用，下次启动生效。
     */
    fun save(info: DeviceSpoofAppInfo, enabled: Boolean, data: DeviceSpoofConfig.Data) {
        viewModelScope.launch(Dispatchers.IO) {
            DeviceSpoofConfig.save(0, info.packageName, enabled, data)
            BXposedManager.get().killPackageAsUser(info.packageName, 0)
            refreshItem(info.packageName)
        }
    }

    private fun refreshItem(packageName: String) {
        appsLiveData.postValue(appsLiveData.value?.map {
            if (it.packageName == packageName) {
                it.copy(
                        enabled = DeviceSpoofConfig.isEnabled(0, packageName),
                        fieldCount = DeviceSpoofConfig.getConfig(0, packageName).fieldCount()
                )
            } else {
                it
            }
        })
    }
}
