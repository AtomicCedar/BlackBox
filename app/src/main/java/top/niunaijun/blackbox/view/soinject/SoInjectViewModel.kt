package top.niunaijun.blackbox.view.soinject

import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.bean.SoInjectAppInfo
import top.niunaijun.blackbox.core.SoInjectConfig
import top.niunaijun.blackbox.fake.frameworks.BXposedManager

class SoInjectViewModel : ViewModel() {

    val appsLiveData = MutableLiveData<List<SoInjectAppInfo>>()

    val resultLiveData = MutableLiveData<String?>()

    fun getApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packageManager = BlackBoxCore.getPackageManager()
            val apps = BlackBoxCore.get().getInstalledApplications(PackageManager.GET_META_DATA, 0)
                    .filterNot { it.packageName == BlackBoxCore.getHostPkg() }
            val result = apps.map {
                SoInjectAppInfo(
                        it.loadLabel(packageManager).toString(),
                        it.packageName,
                        SoInjectConfig.isEnabled(0, it.packageName),
                        SoInjectConfig.getSoFiles(0, it.packageName).size,
                        it.loadIcon(packageManager)
                )
            }.sortedBy { it.name }
            appsLiveData.postValue(result)
        }
    }

    /**
     * 添加一个 so 文件（从文件选择器复制进分身体 lib 目录，自动启用）。
     */
    fun addSo(info: SoInjectAppInfo, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val error = SoInjectConfig.addSoFile(0, info.packageName, uri)
            resultLiveData.postValue(error)
            if (error == null) {
                BXposedManager.get().killPackageAsUser(info.packageName, 0)
            }
            refreshItem(info.packageName)
        }
    }

    fun removeSo(info: SoInjectAppInfo, soName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SoInjectConfig.removeSoFile(0, info.packageName, soName)
            BXposedManager.get().killPackageAsUser(info.packageName, 0)
            refreshItem(info.packageName)
        }
    }

    /**
     * 开关：只切换启用状态（保留已配置的 so 列表），变更后结束该应用，下次启动生效。
     */
    fun setEnabled(info: SoInjectAppInfo, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            SoInjectConfig.setEnabled(0, info.packageName, enabled)
            BXposedManager.get().killPackageAsUser(info.packageName, 0)
            refreshItem(info.packageName)
        }
    }

    private fun refreshItem(packageName: String) {
        appsLiveData.postValue(appsLiveData.value?.map {
            if (it.packageName == packageName) {
                it.copy(
                        enabled = SoInjectConfig.isEnabled(0, packageName),
                        soCount = SoInjectConfig.getSoFiles(0, packageName).size
                )
            } else {
                it
            }
        })
    }
}
