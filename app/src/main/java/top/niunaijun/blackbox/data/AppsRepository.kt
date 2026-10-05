package top.niunaijun.blackbox.data

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.webkit.URLUtil
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.BlackBoxCore.getPackageManager
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.hotfix.HotfixManager
import top.niunaijun.blackbox.utils.AbiUtils
import top.niunaijun.blackbox.utils.FileUtils
import top.niunaijun.blackbox.ui.R
import top.niunaijun.blackbox.app.App
import top.niunaijun.blackbox.app.AppManager
import top.niunaijun.blackbox.bean.AppInfo
import top.niunaijun.blackbox.bean.InstalledAppBean
import top.niunaijun.blackbox.util.getString
import java.io.File
import java.util.concurrent.Executors


/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 23:05
 */

class AppsRepository {
    val TAG: String = "AppsRepository"

    companion object {
        // 图标进程级缓存：同一进程内重进列表/切换用户免重复解析 APK 图标
        private val sIconCache = object : LruCache<String, Drawable>(128) {}
        // 固定小并发池：loadIcon 走系统 PMS binder（线程有限），并发过大会打爆 binder 线程
        private val ICON_POOL = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() / 2))
        // 宿主应用预览池（ListActivity 添加页/模块页用）：与主列表 ICON_POOL 分离。
        // WelcomeActivity 每次冷启动都会预热宿主全量应用（几百个，逐个开 APK 扫描 +
        // binder 解析图标），若与主列表共用池子，主列表的分身体图标任务会被预览任务
        // 挤在队列尾饿死 → 主列表迟迟不显示（退出黑盒重开图标消失；点➕回来预览已
        // 跑完、池子空闲才恢复）。独立小池互不抢占。
        private val PREVIEW_POOL = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 4))
    }
    private var mInstalledList = mutableListOf<AppInfo>()

    fun previewInstallList() {
        synchronized(mInstalledList) {
            val installedApplications: List<ApplicationInfo> =
                getPackageManager().getInstalledApplications(0)
            val pm = getPackageManager()

            // 宿主应用全量列表：ABI 扫描与 isXposedModule 都要逐个开 APK zip、图标走
            // binder+资源解析，串行是慢点——并行构建（过滤 SYSTEM 与宿主自身后并发处理，结果保序）。
            // 用独立 PREVIEW_POOL：不占用主列表 ICON_POOL，避免预览挤掉主列表图标任务
            val futures = installedApplications
                .filterNot { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        || it.packageName == BlackBoxCore.getHostPkg() }
                .map { app ->
                    PREVIEW_POOL.submit<AppInfo?> {
                        val file = File(app.sourceDir)
                        if (!AbiUtils.isSupport(file)) {
                            return@submit null
                        }
                        val isXpModule = BlackBoxCore.get().isXposedModule(file)
                        val icon = sIconCache.get(app.packageName) ?: run {
                            val loaded = app.loadIcon(pm)
                            sIconCache.put(app.packageName, loaded)
                            loaded
                        }
                        AppInfo(
                            app.loadLabel(pm).toString(),
                            icon,
                            app.packageName,
                            app.sourceDir,
                            isXpModule
                        )
                    }
                }
            val installedList = futures.map { it.get() }.filterNotNull().toMutableList()

            this.mInstalledList.clear()
            this.mInstalledList.addAll(installedList)
        }


    }

    fun getInstalledAppList(
        userID: Int,
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        loadingLiveData.postValue(true)
        synchronized(mInstalledList) {
            val blackBoxCore = BlackBoxCore.get()
            Log.d(TAG, mInstalledList.joinToString(","))
            val newInstalledList = mInstalledList.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    blackBoxCore.isInstalled(it.packageName, userID)
                )
            }
            appsLiveData.postValue(newInstalledList)
            loadingLiveData.postValue(false)


        }

    }

    fun getInstalledModuleList(
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {

        loadingLiveData.postValue(true)
        synchronized(mInstalledList) {
            val blackBoxCore = BlackBoxCore.get()
            val moduleList = mInstalledList.filter {
                it.isXpModule
            }.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    blackBoxCore.isInstalledXposedModule(it.packageName)
                )
            }
            appsLiveData.postValue(moduleList)
            loadingLiveData.postValue(false)
        }

    }


    fun getVmInstallList(userId: Int, appsLiveData: MutableLiveData<List<AppInfo>>) {
        val sortListData =
            AppManager.mRemarkSharedPreferences.getString("AppList$userId", "")
        val sortList = sortListData?.split(",")

        // :black 服务进程冷启动时 BPackageManagerService 还在 scanPackage，
        // binder 调用可能短暂抛连接异常——仅对异常重试；结果为空的空列表是
        // 合法状态（未添加任何应用 / 已全部卸载），绝不能当"未就绪"重试，
        // 否则卸载最后一个应用后列表会延迟刷新（用户看到"还在"）
        var applicationList = mutableListOf<ApplicationInfo>()
        for (attempt in 0 until 3) {
            try {
                applicationList = BlackBoxCore.get().getInstalledApplications(0, userId).toMutableList()
                break
            } catch (t: Throwable) {
                // 服务未连接：继续重试
                Thread.sleep(300)
            }
        }

        if (!sortList.isNullOrEmpty()) {
            applicationList.sortWith(AppsSortComparator(sortList))
        }

        // loadLabel/loadIcon 每个都要过 binder + 打开分身 APK 解析图标，串行是加载慢的主因：
        // 先按自定义顺序排好再并行装载（固定小池，避免打爆系统 binder 线程）+ 图标进程级缓存
        val pm = getPackageManager()
        val xposedPkgs = BlackBoxCore.get().installedXPModules.map { it.packageName }.toSet()
        val futures = applicationList.map { app ->
            ICON_POOL.submit<AppInfo> {
                val icon = try {
                    sIconCache.get(app.packageName) ?: run {
                        val loaded = app.loadIcon(pm)
                        sIconCache.put(app.packageName, loaded)
                        loaded
                    }
                } catch (t: Throwable) {
                    // 分身体的 ApplicationInfo 来自容器 PMS，宿主 PM 按包名解析图标
                    // 可能失败（外部导入/宿主未装同包名）。先试宿主同包名图标
                    // （分身体多为宿主导入，宿主必有），再退系统默认图标——应用条目
                    // 必须保留，宁可图标兜底也不能让列表项消失
                    try {
                        sIconCache.get(app.packageName) ?: run {
                            val loaded = pm.getApplicationIcon(app.packageName)
                            sIconCache.put(app.packageName, loaded)
                            loaded
                        }
                    } catch (e: Throwable) {
                        pm.defaultActivityIcon
                    }
                }
                val label = try {
                    app.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    // 标签解析失败：用包名兜底，应用条目必须保留
                    app.packageName
                }
                AppInfo(
                        label,
                        icon,
                        app.packageName,
                        app.sourceDir,
                        xposedPkgs.contains(app.packageName)
                )
            }
        }
        // 兜底后基本不会抛异常，仍防御极端情况：单个任务失败不影响其他应用
        val appInfoList = mutableListOf<AppInfo>()
        for (future in futures) {
            try {
                future.get()?.let { appInfoList.add(it) }
            } catch (t: Throwable) {
                // 单个任务极端异常：跳过该应用，不拖垮整个列表
            }
        }

        appsLiveData.postValue(appInfoList)
    }


    fun installApk(source: String, userId: Int, resultLiveData: MutableLiveData<String>) {
        val blackBoxCore = BlackBoxCore.get()
        try {
            val installResult = if (URLUtil.isValidUrl(source)) {
                val uri = Uri.parse(source)
                blackBoxCore.installPackageAsUser(uri, userId)
            } else {
                blackBoxCore.installPackageAsUser(source, userId)
            }

            if (installResult.success) {
                updateAppSortList(userId, installResult.packageName, true)
                resultLiveData.postValue(getString(R.string.install_success))
            } else {
                resultLiveData.postValue(getString(R.string.install_fail, installResult.msg))
            }
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.install_fail, e.message ?: e.javaClass.simpleName))
        }
        scanUser()
    }

    fun unInstall(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userID)
            updateAppSortList(userID, packageName, false)
            scanUser()
            resultLiveData.postValue(getString(R.string.uninstall_success))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.uninstall_fail))
        }
    }


    fun launchApk(packageName: String, userId: Int, launchLiveData: MutableLiveData<Boolean>) {
        val result = BlackBoxCore.get().launchApk(packageName, userId)
        launchLiveData.postValue(result)
    }


    fun clearApkData(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().clearPackage(packageName, userID)
            resultLiveData.postValue(getString(R.string.clear_success))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.clear_fail))
        }
    }

    /**
     * 热修复补丁：宿主把用户选择的 dex 拷进 blackbox 根目录 hotfix/u<userId>/<pkg>.dex，
     * 分身进程每次启动时由 HotfixManager 前插到应用类加载器的 dexElements
     */
    fun getHotfixPatch(userId: Int, packageName: String): File? {
        val patch = BEnvironment.getHotfixPatchFile(userId, packageName)
        return if (patch.isFile) patch else null
    }

    fun saveHotfixPatch(userId: Int, packageName: String, uri: Uri, resultLiveData: MutableLiveData<String>) {
        try {
            val patch = BEnvironment.getHotfixPatchFile(userId, packageName)
            FileUtils.mkdirs(patch.parentFile!!.absolutePath)
            App.getContext().contentResolver.openInputStream(uri)!!.use { input ->
                patch.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // 覆盖旧补丁后清掉旧 odex 缓存，避免新旧内容不一致
            HotfixManager.clearOdexCache(packageName, userId)
            resultLiveData.postValue(getString(R.string.hotfix_saved))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.hotfix_save_fail, e.message ?: e.javaClass.simpleName))
        }
    }

    fun clearHotfixPatch(userId: Int, packageName: String, resultLiveData: MutableLiveData<String>) {
        try {
            if (!BEnvironment.getHotfixPatchFile(userId, packageName).exists()) {
                resultLiveData.postValue(getString(R.string.hotfix_none))
                return
            }
            HotfixManager.clearPatch(packageName, userId)
            resultLiveData.postValue(getString(R.string.hotfix_cleared))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.hotfix_clear_fail))
        }
    }

    /**
     * 倒序递归扫描用户，
     * 如果用户是空的，就删除用户，删除用户备注，删除应用排序列表
     */
    private fun scanUser() {
        val blackBoxCore = BlackBoxCore.get()
        val userList = blackBoxCore.users

        if (userList.isEmpty()) {
            return
        }

        val id = userList.last().id

        // user 0 是默认根用户，删除会导致容器无用户可用（本体 UI 状态异常），永不删除
        if (id > 0 && blackBoxCore.getInstalledApplications(0, id).isEmpty()) {
            blackBoxCore.deleteUser(id)
            AppManager.mRemarkSharedPreferences.edit {
                remove("Remark$id")
                remove("AppList$id")
            }
            scanUser()
        }
    }


    /**
     * 更新排序列表
     * @param userID Int
     * @param pkg String
     * @param isAdd Boolean true是添加，false是移除
     */
    private fun updateAppSortList(userID: Int, pkg: String, isAdd: Boolean) {

        val savedSortList =
            AppManager.mRemarkSharedPreferences.getString("AppList$userID", "")

        val sortList = linkedSetOf<String>()
        if (savedSortList != null) {
            sortList.addAll(savedSortList.split(","))
        }

        if (isAdd) {
            sortList.add(pkg)
        } else {
            sortList.remove(pkg)
        }

        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID", sortList.joinToString(","))
        }

    }

    /**
     * 保存排序后的apk顺序
     */
    fun updateApkOrder(userID: Int, dataList: List<AppInfo>) {
        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID",
                dataList.joinToString(",") { it.packageName })
        }

    }

}
