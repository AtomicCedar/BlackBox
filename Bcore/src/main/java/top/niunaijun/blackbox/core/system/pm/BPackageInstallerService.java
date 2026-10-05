package top.niunaijun.blackbox.core.system.pm;

import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.installer.CopyExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.CreatePackageExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.CreateUserExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.Executor;
import top.niunaijun.blackbox.core.system.pm.installer.RemoveAppExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.RemoveUserExecutor;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Created by Milk on 4/21/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BPackageInstallerService extends IBPackageInstallerService.Stub implements ISystemService {
    private static final BPackageInstallerService sService = new BPackageInstallerService();

    public static BPackageInstallerService get() {
        return sService;
    }

    public static final String TAG = "BPackageInstallerService";

    @Override
    public int installPackageAsUser(BPackageSettings ps, int userId) {
        List<Executor> executors = new ArrayList<>();
        // 创建用户环境相关操作
        executors.add(new CreateUserExecutor());
        // 创建应用环境相关操作
        executors.add(new CreatePackageExecutor());
        // 拷贝应用相关文件
        executors.add(new CopyExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, userId);
            Slog.d(TAG, "installPackageAsUser: " + executor.getClass().getSimpleName() + " exec: " + exec);
            if (exec != 0) {
                return exec;
            }
        }
        return 0;
    }

    @Override
    public int uninstallPackageAsUser(BPackageSettings ps, boolean removeApp, int userId) {
        // 卸载时清理该应用的按应用配置（设备伪装/so 注入/maps 隐藏/libc hook 及注入 so 目录），
        // 避免卸载后重装残留旧配置仍然生效
        deleteHotfixConfig(ps.pkg.packageName, userId);
        List<Executor> executors = new ArrayList<>();
        if (removeApp) {
            // 移除App
            executors.add(new RemoveAppExecutor());
        }
        // 移除用户相关目录
        executors.add(new RemoveUserExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, userId);
            Slog.d(TAG, "uninstallPackageAsUser: " + executor.getClass().getSimpleName() + " exec: " + exec);
            if (exec != 0) {
                return exec;
            }
        }
        return 0;
    }

    private void deleteHotfixConfig(String packageName, int userId) {
        try {
            BEnvironment.getDeviceSpoofFile(userId, packageName).delete();
            BEnvironment.getSoInjectFile(userId, packageName).delete();
            BEnvironment.getSoInjectListFile(userId, packageName).delete();
            BEnvironment.getMapsHideFile(userId, packageName).delete();
            BEnvironment.getLibcHookDisableFile(userId, packageName).delete();
            FileUtils.deleteDir(BEnvironment.getAppLibDir(packageName));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int clearPackage(BPackageSettings ps, int userId) {
        List<Executor> executors = new ArrayList<>();
        // 移除用户相关目录
        executors.add(new RemoveUserExecutor());
        // 创建用户环境相关操作
        executors.add(new CreateUserExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, userId);
            Slog.d(TAG, "uninstallPackageAsUser: " + executor.getClass().getSimpleName() + " exec: " + exec);
            if (exec != 0) {
                return exec;
            }
        }
        return 0;
    }

    @Override
    public int updatePackage(BPackageSettings ps) {
        List<Executor> executors = new ArrayList<>();
        executors.add(new CreatePackageExecutor());
        executors.add(new CopyExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, -1);
            if (exec != 0) {
                return exec;
            }
        }
        return 0;
    }

    @Override
    public void systemReady() {

    }
}
