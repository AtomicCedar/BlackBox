package top.niunaijun.blackbox.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * 按应用启用 /proc/self/maps 扩展规则（删行/改行伪装）的开关。
 * <p>
 * 配置是一个规则文件：blackbox/hotfix/maps_hide/u&lt;userId&gt;/&lt;packageName&gt;，
 * 与热修复补丁同思路——同 uid 直接读文件，无需跨进程调用，进程每次启动都拿到
 * 最新值。文件每行一条规则：
 * <pre>
 * r 关键字   删行：含关键字的行整行丢弃
 * m 旧?新    改行：行内匹配的文本替换
 * </pre>
 * 引擎 so（libpine/libblackbox）始终隐藏，不在此文件配置范围内。规则内容由
 * 用户在 UI 中自行填写，空文件表示启用但暂未配置扩展规则。
 */
public class MapsHideConfig {

    public static boolean isEnabled(int userId, String packageName) {
        try {
            return BEnvironment.getMapsHideFile(userId, packageName).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 设置启用状态。启用时创建（空）规则文件，禁用时删除文件。
     */
    public static void setEnabled(int userId, String packageName, boolean enabled) {
        File file = BEnvironment.getMapsHideFile(userId, packageName);
        try {
            if (enabled) {
                FileUtils.mkdirs(file.getParentFile().getAbsolutePath());
                if (!file.exists()) {
                    file.createNewFile();
                }
            } else if (file.exists()) {
                file.delete();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * 保存规则文本（启用=写文件，禁用=删文件）。内容原样落盘，UI 编辑即存。
     */
    public static void setRules(int userId, String packageName, boolean enabled, String rulesText) {
        File file = BEnvironment.getMapsHideFile(userId, packageName);
        try {
            if (enabled) {
                FileUtils.mkdirs(file.getParentFile().getAbsolutePath());
                FileUtils.writeToFile(
                        (rulesText == null ? "" : rulesText).getBytes(StandardCharsets.UTF_8), file);
            } else if (file.exists()) {
                file.delete();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * 读取规则文件全文（含用户写的注释与格式），未启用返回空串，用于编辑回显。
     */
    public static String getRulesText(int userId, String packageName) {
        try {
            File file = BEnvironment.getMapsHideFile(userId, packageName);
            if (!file.exists()) {
                return "";
            }
            return FileUtils.readToString(file.getAbsolutePath());
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 读取规则文件，每行一条；空行与 # 注释忽略。
     */
    public static List<String> getRules(int userId, String packageName) {
        List<String> rules = new ArrayList<>();
        try {
            File file = BEnvironment.getMapsHideFile(userId, packageName);
            if (!file.exists()) {
                return rules;
            }
            String content = FileUtils.readToString(file.getAbsolutePath());
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                rules.add(trimmed);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return rules;
    }
}
