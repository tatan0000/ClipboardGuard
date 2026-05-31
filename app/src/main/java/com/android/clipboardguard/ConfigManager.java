package com.android.clipboardguard;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * system_server 侧配置与模块状态存储：/data/system/clipboardguard/
 *
 * 开机：Hook 主动 loadFromDataSystem() 读文件进内存。
 * 运行时：App 发 {@link PermissionProvider#ACTION_CONFIG_CHANGED} 广播，
 *         system_server 收广播后 persistFromBroadcast() 落盘 + 刷新内存。
 */
public final class ConfigManager {

    private static final String TAG = "ClipboardGuard.Config";

    public static final String CONFIG_DIR = "/data/system/clipboardguard";
    /** 模块状态文件（JSON）：Hook 成功时写入，App 通过 Binder IPC 获取。 */
    public static final String MODULE_STATUS_FILE_NAME = "module_status.json";
    /** Hook 运行时开关：拒绝读取 Toast、LSPosed 日志输出 */
    public static final String GLOBAL_FLAGS_FILE_NAME = "global_flags.json";
    /** @deprecated 旧文件名，加载时自动迁移 */
    @Deprecated
    private static final String LEGACY_GLOBAL_FLAGS_FILE_NAME = "config.json";

    private ConfigManager() {}

    // ──────────────────────────── 开机 / 重载 ────────────────────────────

    /** Hook 启动时从 /data/system/clipboardguard/ 加载配置到内存。 */
    public static synchronized boolean loadFromDataSystem() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            XLog.w(TAG, "配置目录不存在: " + CONFIG_DIR);
            return false;
        }
        try {
            applyBlocklistsFromDisk(dir);
            applyRulesFromDisk(dir);
            applyGlobalFlagsFromDisk(dir);
            XLog.i(TAG, "loadFromDataSystem 完成");
            return true;
        } catch (Throwable e) {
            XLog.e(TAG, "loadFromDataSystem 失败: " + e.getMessage());
            return false;
        }
    }

    /** 仅从磁盘重读（例如收到空广播触发 reload）。 */
    public static synchronized void reloadFromDataSystem() {
        loadFromDataSystem();
    }

    // ──────────────────────────── 广播 → 落盘 + 内存 ────────────────────────────

    /**
     * 处理 App 发来的配置变更广播：更新内存并写入 /data/system/clipboardguard/。
     */
    public static synchronized void applyConfigBroadcast(Intent intent) {
        if (intent == null) {
            reloadFromDataSystem();
            return;
        }

        ArrayList<String> writeBlocklist = intent.getStringArrayListExtra(
                PermissionProvider.CALL_KEY_WRITE_BLOCKLIST);
        if (intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_BLOCKLIST)) {
            PermissionCache.updateFromWriteBlockList(
                    writeBlocklist != null ? writeBlocklist : new ArrayList<>());
            saveBlocklistFile("write_blocklist.txt",
                    writeBlocklist != null ? writeBlocklist : new ArrayList<>());
        }

        ArrayList<String> readBlocklist = intent.getStringArrayListExtra(
                PermissionProvider.CALL_KEY_READ_BLOCKLIST);
        if (intent.hasExtra(PermissionProvider.CALL_KEY_READ_BLOCKLIST)) {
            PermissionCache.updateFromReadBlockList(
                    readBlocklist != null ? readBlocklist : new ArrayList<>());
            saveBlocklistFile("read_blocklist.txt",
                    readBlocklist != null ? readBlocklist : new ArrayList<>());
        }

        String writeRulesJson = intent.getStringExtra(PermissionProvider.CALL_KEY_WRITE_RULES_JSON);
        if (intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_RULES_JSON)
                && writeRulesJson != null && !writeRulesJson.isEmpty()) {
            try {
                saveTextFile("write_rules.json", writeRulesJson);
            } catch (IOException e) {
                XLog.w(TAG, "保存 write_rules.json 失败: " + e.getMessage());
            }
        }

        String writeDefaultJson = intent.getStringExtra(PermissionProvider.CALL_KEY_WRITE_DEFAULT_RULES_JSON);
        if (intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_DEFAULT_RULES_JSON)
                && writeDefaultJson != null && !writeDefaultJson.isEmpty()) {
            try {
                saveTextFile("write_default_rules.json", writeDefaultJson);
            } catch (IOException e) {
                XLog.w(TAG, "保存 write_default_rules.json 失败: " + e.getMessage());
            }
        }

        String readRulesJson = intent.getStringExtra(PermissionProvider.CALL_KEY_READ_RULES_JSON);
        if (intent.hasExtra(PermissionProvider.CALL_KEY_READ_RULES_JSON)
                && readRulesJson != null && !readRulesJson.isEmpty()) {
            try {
                saveTextFile("read_rules.json", readRulesJson);
            } catch (IOException e) {
                XLog.w(TAG, "保存 read_rules.json 失败: " + e.getMessage());
            }
        }

        String readDefaultJson = intent.getStringExtra(PermissionProvider.CALL_KEY_READ_DEFAULT_RULES_JSON);
        if (intent.hasExtra(PermissionProvider.CALL_KEY_READ_DEFAULT_RULES_JSON)
                && readDefaultJson != null && !readDefaultJson.isEmpty()) {
            try {
                saveTextFile("read_default_rules.json", readDefaultJson);
            } catch (IOException e) {
                XLog.w(TAG, "保存 read_default_rules.json 失败: " + e.getMessage());
            }
        }

        if (intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_RULES_JSON)
                || intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_DEFAULT_RULES_JSON)
                || intent.hasExtra(PermissionProvider.CALL_KEY_READ_RULES_JSON)
                || intent.hasExtra(PermissionProvider.CALL_KEY_READ_DEFAULT_RULES_JSON)) {
            applyMergedRulesFromDisk(new File(CONFIG_DIR));
        }

        boolean toastChanged = intent.hasExtra(PermissionProvider.CALL_KEY_READ_BLOCKED_TOAST_ENABLED);
        boolean logChanged = intent.hasExtra(PermissionProvider.CALL_KEY_LSPOSED_LOG_ENABLED);
        if (toastChanged || logChanged) {
            boolean toast = intent.getBooleanExtra(
                    PermissionProvider.CALL_KEY_READ_BLOCKED_TOAST_ENABLED,
                    PermissionCache.isReadBlockedToastEnabled());
            boolean log = intent.getBooleanExtra(
                    PermissionProvider.CALL_KEY_LSPOSED_LOG_ENABLED,
                    PermissionCache.isLsposedLogEnabled());
            PermissionCache.updateGlobalFlags(toast, log);
            saveGlobalFlags(toast, log);
            XLog.d(TAG, "开关状态已同步: 读取拦截Toast：" + (toast ? "已开启" : "已关闭")
                    + ", 剪贴板内容输出：" + (log ? "已开启" : "已关闭"));
        }

        if (!intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_BLOCKLIST)
                && !intent.hasExtra(PermissionProvider.CALL_KEY_READ_BLOCKLIST)
                && !intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_RULES_JSON)
                && !intent.hasExtra(PermissionProvider.CALL_KEY_WRITE_DEFAULT_RULES_JSON)
                && !intent.hasExtra(PermissionProvider.CALL_KEY_READ_RULES_JSON)
                && !intent.hasExtra(PermissionProvider.CALL_KEY_READ_DEFAULT_RULES_JSON)
                && !intent.hasExtra(PermissionProvider.CALL_KEY_READ_BLOCKED_TOAST_ENABLED)
                && !intent.hasExtra(PermissionProvider.CALL_KEY_LSPOSED_LOG_ENABLED)) {
            reloadFromDataSystem();
        }
    }

    // ──────────────────────────── 模块状态文件（module_status.json） ────────────────────────────

    /**
     * [system_server] Hook 成功后写入模块状态 JSON（含 boot_id）到主副本。
     * <p>
     * 读取策略（App 侧）：Binder IPC — ServiceManager.getService("clipboard")
     * + transact(CBGUARD_STATUS) 直连 system_server。
     * <p>
     * 返回 JSON 字符串，由调用方存入 sModuleStatusJson 供 onTransact 返回。
     */
    public static synchronized String saveModuleStatus(String source, String target, int xposedApi, int pid) {
        try {
            String bootId = readCurrentBootId();
            JSONObject state = new JSONObject();
            state.put("active", true);
            state.put("boot_id", bootId);
            state.put("last_time", System.currentTimeMillis());
            state.put("last_elapsed", SystemClock.elapsedRealtime());
            state.put("pid", pid);
            state.put("source", source);
            state.put("target", target);
            state.put("xposed_api", xposedApi);
            String json = state.toString();

            // 写入主副本：/data/system/clipboardguard/（system_server 内部使用）
            saveTextFile(MODULE_STATUS_FILE_NAME, json);

            XLog.i(TAG, "[Alive] 模块状态已写入, boot_id=" + bootId);
            return json;
        } catch (Exception e) {
            XLog.w(TAG, "saveModuleStatus 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 读取当前系统的 boot_id（/proc/sys/kernel/random/boot_id）。
     */
    public static String readCurrentBootId() {
        File bootIdFile = new File("/proc/sys/kernel/random/boot_id");
        String id = readTextFile(bootIdFile);
        return id != null ? id.trim() : "";
    }

    // ──────────────────────────── 磁盘 → 内存 ────────────────────────────

    private static void applyBlocklistsFromDisk(File dir) throws IOException {
        List<String> writeList = readBlocklistFile(new File(dir, "write_blocklist.txt"));
        PermissionCache.updateFromWriteBlockList(new ArrayList<>(writeList));

        List<String> readList = readBlocklistFile(new File(dir, "read_blocklist.txt"));
        PermissionCache.updateFromReadBlockList(new ArrayList<>(readList));
    }

    private static void applyRulesFromDisk(File dir) {
        applyMergedRulesFromDisk(dir);
    }

    /** 自定义规则与默认规则分文件存储，加载到内存时再合并。 */
    private static void applyMergedRulesFromDisk(File dir) {
        String writeCustom = readTextFile(new File(dir, "write_rules.json"));
        String writeDefault = readTextFile(new File(dir, "write_default_rules.json"));
        String mergedWrite = ContentRulesManager.mergeRulesForRuntime(writeCustom, writeDefault);
        ContentRulesManager.updateWriteRulesFromJson(mergedWrite);

        String readCustom = readTextFile(new File(dir, "read_rules.json"));
        String readDefault = readTextFile(new File(dir, "read_default_rules.json"));
        String mergedRead = ContentRulesManager.mergeRulesForRuntime(readCustom, readDefault);
        ContentRulesManager.updateReadRulesFromJson(mergedRead);
    }

    private static void applyGlobalFlagsFromDisk(File dir) {
        String json = readTextFile(new File(dir, GLOBAL_FLAGS_FILE_NAME));
        if (json == null || json.isEmpty()) {
            json = readTextFile(new File(dir, LEGACY_GLOBAL_FLAGS_FILE_NAME));
        }
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject flags = new JSONObject(json);
            PermissionCache.updateGlobalFlags(
                    flags.optBoolean("read_blocked_toast_enabled", true),
                    flags.optBoolean("lsposed_log_enabled", true));
        } catch (Exception ignored) {}
    }

    // ──────────────────────────── 内存 → 磁盘 ────────────────────────────

    private static void saveBlocklistFile(String fileName, List<String> pkgs) {
        try {
            ensureConfigDir();
            File file = new File(CONFIG_DIR, fileName);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write("# ClipboardGuard Blocklist");
                writer.newLine();
                for (String pkg : pkgs) {
                    writer.write(pkg);
                    writer.newLine();
                }
                writer.flush();
            }
        } catch (IOException e) {
            XLog.w(TAG, "保存 " + fileName + " 失败: " + e.getMessage());
        }
    }

    /** 持久化 Hook 运行时开关（拒绝读取 Toast、LSPosed 日志）。 */
    private static void saveGlobalFlags(boolean readBlockedToast, boolean lsposedLog) {
        try {
            JSONObject json = new JSONObject();
            json.put("read_blocked_toast_enabled", readBlockedToast);
            json.put("lsposed_log_enabled", lsposedLog);
            saveTextFile(GLOBAL_FLAGS_FILE_NAME, json.toString());
        } catch (Exception e) {
            XLog.w(TAG, "保存 " + GLOBAL_FLAGS_FILE_NAME + " 失败: " + e.getMessage());
        }
    }

    private static void saveTextFile(String fileName, String content) throws IOException {
        ensureConfigDir();
        saveTextFileAbsolute(new File(CONFIG_DIR, fileName).getAbsolutePath(), content);
    }

    private static void saveTextFileAbsolute(String absolutePath, String content) {
        try {
            File file = new File(absolutePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                XLog.w(TAG, "无法创建目录: " + parent.getPath());
                return;
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write(content);
                writer.flush();
            }
        } catch (IOException e) {
            XLog.w(TAG, "写入失败 " + absolutePath + ": " + e.getMessage());
        }
    }

    private static void ensureConfigDir() throws IOException {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建 " + CONFIG_DIR);
        }
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
    }

    private static List<String> readBlocklistFile(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!file.exists()) return lines;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                lines.add(line);
            }
        }
        return lines;
    }

    static String readTextFile(File file) {
        if (file == null || !file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
