package com.android.clipboardguard;

import android.annotation.SuppressLint;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ContentRulesManager - 广告内容过滤规则管理器（写入 + 读取）
 *
 * 改造说明（2026-05-03）：
 * - 加载规则时自动合并启用的默认规则（write_default_rules.json / read_default_rules.json）
 * - 添加 isLoaded / isReadLoaded 标记，区分“加载完成”与“列表非空”
 * - 广播/FileObserver 触发刷新时重新合并默认规则
 */
public class ContentRulesManager {

    private static final String TAG = "ClipboardGuard.ContentRules";

    // 配置文件路径（自定义规则）
    @SuppressLint("SdCardPath") // Xposed 环境无 Context 可用
    private static final String CONFIG_FILE = "/data/data/com.android.clipboardguard/files/write_rules.json";
    @SuppressLint("SdCardPath")
    private static final String READ_CONFIG_FILE = "/data/data/com.android.clipboardguard/files/read_rules.json";

    // 默认规则文件
    @SuppressLint("SdCardPath")
    private static final String WRITE_DEFAULT_FILE = "/data/data/com.android.clipboardguard/files/write_default_rules.json";
    @SuppressLint("SdCardPath")
    private static final String READ_DEFAULT_FILE = "/data/data/com.android.clipboardguard/files/read_default_rules.json";

    // ── 写入规则缓存 ──
    private static final List<RulePattern> sRulePatterns = new ArrayList<>();
    private static volatile boolean sEnabled = true;
    private static volatile boolean sLoaded = false;
    private static long sLastModified = 0;
    private static long sLastLoadTime = 0;

    // ── 读取规则缓存 ──
    private static final List<ReadRulePattern> sReadRulePatterns = new ArrayList<>();
    private static volatile boolean sReadEnabled = false;
    private static volatile boolean sReadLoaded = false;
    private static long sReadLastModified = 0;
    private static long sReadLastLoadTime = 0;

    private static final long CACHE_TTL_MS = 5000;

    // ──────────────────────────── 数据类 ────────────────────────────

    public static class RulePattern {
        public String name;
        public java.util.regex.Pattern pattern;
        public boolean enabled;
        RulePattern(String name, String regex, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
            try {
                this.pattern = java.util.regex.Pattern.compile(regex);
            } catch (Exception e) {
                Log.e(TAG, "正则编译失败: " + regex + " -> " + e.getMessage());
                this.pattern = null;
            }
        }
    }

    public static class ReadRulePattern {
        public String name;
        public java.util.regex.Pattern pattern;
        public boolean enabled;
        ReadRulePattern(String name, String regex, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
            try {
                this.pattern = java.util.regex.Pattern.compile(regex);
            } catch (Exception e) {
                Log.e(TAG, "正则编译失败: " + regex + " -> " + e.getMessage());
                this.pattern = null;
            }
        }
    }

    // ──────────────────────────── 加载方法 ────────────────────────────

    /**
     * 加载写入规则（自定义 + 默认启用规则）
     */
    public static synchronized void loadRules() {
        long now = System.currentTimeMillis();
        File file = new File(CONFIG_FILE);

        if (sLastModified == file.lastModified() && (now - sLastLoadTime) < CACHE_TTL_MS && sLoaded) {
            return;
        }

        Log.i(TAG, "开始加载写入规则（合并默认）...");
        sRulePatterns.clear();

        // 1. 加载自定义规则文件
        if (file.exists()) {
            sLastModified = file.lastModified();
            loadRulesFromFile(file);
        }

        // 2. 加载默认规则文件（只取启用的）
        File defaultFile = new File(WRITE_DEFAULT_FILE);
        if (defaultFile.exists()) {
            appendEnabledDefaultRules(defaultFile);
        }

        sLoaded = true;
        sLastLoadTime = System.currentTimeMillis();
        Log.i(TAG, "写入规则加载完成: " + getEnabledRuleCount() + " 条启用，耗时=" + (sLastLoadTime - now) + "ms");
    }

    /**
     * 加载读取规则（自定义 + 默认启用规则）
     */
    public static synchronized void loadReadRules() {
        long now = System.currentTimeMillis();
        File file = new File(READ_CONFIG_FILE);

        if (sReadLastModified == file.lastModified() && (now - sReadLastLoadTime) < CACHE_TTL_MS && sReadLoaded) {
            return;
        }

        Log.i(TAG, "开始加载读取规则（合并默认）...");
        sReadRulePatterns.clear();

        if (file.exists()) {
            sReadLastModified = file.lastModified();
            loadReadRulesFromFile(file);
        }

        File defaultFile = new File(READ_DEFAULT_FILE);
        if (defaultFile.exists()) {
            appendEnabledDefaultReadRules(defaultFile);
        }

        sReadLoaded = true;
        sReadLastLoadTime = System.currentTimeMillis();
        Log.i(TAG, "读取规则加载完成: " + getReadEnabledRuleCount() + " 条启用");
    }

    /**
     * 从 JSON 文件解析写入规则并放入 sRulePatterns
     */
    private static void loadRulesFromFile(File file) {
        try {
            JSONObject root = parseJsonObject(file);
            if (root == null) return;
            sEnabled = root.optBoolean("enabled", true);
            JSONArray arr = root.optJSONArray("content_rules");
            if (arr == null) arr = root.optJSONArray("rules");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    String name = r.optString("name", "未命名");
                    String regex = r.optString("pattern", "");
                    if (regex.isEmpty()) regex = r.optString("regex", "");
                    boolean enabled = r.optBoolean("enabled", true);
                    if (!regex.isEmpty()) {
                        sRulePatterns.add(new RulePattern(name, regex, enabled));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析写入规则失败", e);
            sRulePatterns.clear();
        }
    }

    /**
     * 从 JSON 文件解析读取规则并放入 sReadRulePatterns
     */
    private static void loadReadRulesFromFile(File file) {
        try {
            JSONObject root = parseJsonObject(file);
            if (root == null) return;
            sReadEnabled = root.optBoolean("enabled", false);
            JSONArray arr = root.optJSONArray("content_rules");
            if (arr == null) arr = root.optJSONArray("read_rules");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    String name = r.optString("name", "未命名");
                    String regex = r.optString("pattern", "");
                    if (regex.isEmpty()) regex = r.optString("regex", "");
                    boolean enabled = r.optBoolean("enabled", true);
                    if (!regex.isEmpty()) {
                        sReadRulePatterns.add(new ReadRulePattern(name, regex, enabled));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析读取规则失败", e);
            sReadRulePatterns.clear();
        }
    }

    /**
     * 从默认规则文件中读取启用的规则，追加到写入列表
     */
    private static void appendEnabledDefaultRules(File defaultFile) {
        try {
            JSONArray arr = parseJsonArray(defaultFile);
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                boolean enabled = r.optBoolean("enabled", false);
                if (!enabled) continue;
                String name = r.optString("name", "未命名");
                String regex = r.optString("pattern", "");
                if (regex.isEmpty()) regex = r.optString("regex", "");
                if (!regex.isEmpty()) {
                    sRulePatterns.add(new RulePattern(name, regex, true));
                }
            }
            Log.i(TAG, "追加启用默认写入规则");
        } catch (Exception e) {
            Log.e(TAG, "解析默认写入规则失败", e);
        }
    }

    /**
     * 从默认读取规则文件中读取启用的规则，追加到读取列表
     */
    private static void appendEnabledDefaultReadRules(File defaultFile) {
        try {
            JSONArray arr = parseJsonArray(defaultFile);
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                boolean enabled = r.optBoolean("enabled", false);
                if (!enabled) continue;
                String name = r.optString("name", "未命名");
                String regex = r.optString("pattern", "");
                if (regex.isEmpty()) regex = r.optString("regex", "");
                if (!regex.isEmpty()) {
                    sReadRulePatterns.add(new ReadRulePattern(name, regex, true));
                }
            }
            Log.i(TAG, "追加启用默认读取规则");
        } catch (Exception e) {
            Log.e(TAG, "解析默认读取规则失败", e);
        }
    }

    // ──────────────────────────── JSON 解析工具 ────────────────────────────

    private static JSONObject parseJsonObject(File file) throws Exception {
        String content = readFileContent(file);
        if (content == null || content.isEmpty()) return null;
        return new JSONObject(content);
    }

    private static JSONArray parseJsonArray(File file) throws Exception {
        String content = readFileContent(file);
        if (content == null || content.isEmpty()) return null;
        return new JSONArray(content);
    }

    private static String readFileContent(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    // ──────────────────────────── 查询接口 ────────────────────────────

    /**
     * 检查文本是否匹配广告规则
     * @return 匹配的规则名称，未匹配返回 null
     */
    public static String matchesAdContent(String text) {
        if (!sEnabled) return null;
        if (text == null || text.isEmpty()) return null;
        if (sRulePatterns.isEmpty()) return null;
        if (text.length() > 5000) return null;

        for (RulePattern rule : sRulePatterns) {
            if (!rule.enabled || rule.pattern == null) continue;
            try {
                if (rule.pattern.matcher(text).find()) return rule.name;
            } catch (Exception e) {
                Log.e(TAG, "正则匹配异常: " + rule.name);
            }
        }
        return null;
    }

    public static boolean isEnabled() { return sEnabled; }
    public static int getEnabledRuleCount() {
        int cnt = 0;
        for (RulePattern r : sRulePatterns) if (r.enabled) cnt++;
        return cnt;
    }

    public static boolean hasEnabledRule() {
        for (RulePattern r : sRulePatterns) if (r.enabled && r.pattern != null) return true;
        return false;
    }

    public static boolean isLoaded() { return sLoaded; }

    // ──────────────────────────── 广播更新接口 ────────────────────────────

    /**
     * 从广播 JSON 完全替换规则列表
     */
    public static synchronized void updateRulesFromJson(String json, boolean isReadRules) {
        if (json == null || json.isEmpty()) {
            if (isReadRules) {
                sReadRulePatterns.clear();
                sReadEnabled = false;
                sReadLoaded = true;
            } else {
                sRulePatterns.clear();
                sEnabled = false;
                sLoaded = true;
            }
            return;
        }
        try {
            JSONObject root = new JSONObject(json);
            if (isReadRules) {
                sReadRulePatterns.clear();
                sReadEnabled = root.optBoolean("enabled", false);
                JSONArray arr = root.optJSONArray("content_rules");
                if (arr == null) arr = root.optJSONArray("read_rules");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.getJSONObject(i);
                        String name = r.optString("name", "");
                        String regex = r.optString("pattern", "");
                        if (regex.isEmpty()) regex = r.optString("regex", "");
                        if (!regex.isEmpty()) {
                            sReadRulePatterns.add(new ReadRulePattern(name, regex, r.optBoolean("enabled", true)));
                        }
                    }
                }
                sReadLoaded = true;
            } else {
                sRulePatterns.clear();
                sEnabled = root.optBoolean("enabled", true);
                JSONArray arr = root.optJSONArray("content_rules");
                if (arr == null) arr = root.optJSONArray("rules");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.getJSONObject(i);
                        String name = r.optString("name", "");
                        String regex = r.optString("pattern", "");
                        if (regex.isEmpty()) regex = r.optString("regex", "");
                        if (!regex.isEmpty()) {
                            sRulePatterns.add(new RulePattern(name, regex, r.optBoolean("enabled", true)));
                        }
                    }
                }
                sLoaded = true;
            }
            Log.d(TAG, (isReadRules ? "读取" : "写入") + "规则通过广播更新完成: "
                    + (isReadRules ? getReadEnabledRuleCount() : getEnabledRuleCount()) + " 条启用");
        } catch (Exception e) {
            Log.e(TAG, "解析规则 JSON 失败", e);
            if (isReadRules) {
                sReadRulePatterns.clear();
                sReadEnabled = false;
                sReadLoaded = true;
            } else {
                sRulePatterns.clear();
                sEnabled = false;
                sLoaded = true;
            }
        }
    }

    @SuppressWarnings("unused") // 兼容旧调用
    public static synchronized void updateRulesFromJson(String json) {
        updateRulesFromJson(json, false);
    }

    public static synchronized void updateReadRulesFromJson(String json) {
        updateRulesFromJson(json, true);
    }

    // ──────────────────────────── 读取规则专有方法 ────────────────────────────

    @SuppressWarnings("unused") // 由 ReadHook 调用
    public static String matchesReadContent(String text) {
        if (!sReadEnabled) return null;
        if (text == null || text.isEmpty()) return null;
        if (sReadRulePatterns.isEmpty()) return null;
        if (text.length() > 5000) return null;
        for (ReadRulePattern rule : sReadRulePatterns) {
            if (!rule.enabled || rule.pattern == null) continue;
            try {
                if (rule.pattern.matcher(text).find()) return rule.name;
            } catch (Exception e) {
                Log.e(TAG, "正则匹配异常: " + rule.name);
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    public static boolean isReadEnabled() { return sReadEnabled; }
    @SuppressWarnings("unused")
    public static int getReadEnabledRuleCount() {
        int cnt = 0;
        for (ReadRulePattern r : sReadRulePatterns) if (r.enabled) cnt++;
        return cnt;
    }
    @SuppressWarnings("unused")
    public static boolean hasEnabledReadRule() {
        for (ReadRulePattern r : sReadRulePatterns) if (r.enabled && r.pattern != null) return true;
        return false;
    }
    @SuppressWarnings("unused")
    public static boolean isReadLoaded() { return sReadLoaded; }

    // ──────────────────────────── 持久化（App 端调用） ────────────────────────────

    @SuppressWarnings("unused") // 由 App 端 WriteRulesDetailActivity 间接调用
    public static boolean saveRules(String rulesJson) {
        File file = new File(CONFIG_FILE);
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            new JSONObject(rulesJson);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(rulesJson);
            }
            sLastModified = file.lastModified();
            loadRules();
            Log.i(TAG, "写入规则保存成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "保存写入规则失败", e);
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static String getRulesJson() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return getDefaultRulesJson();
        try {
            return readFileContent(file);
        } catch (Exception e) {
            return getDefaultRulesJson();
        }
    }

    private static String getDefaultRulesJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("enabled", true);
            root.put("content_rules", new JSONArray());
            return root.toString(4);
        } catch (Exception e) {
            return "{\"version\":1,\"enabled\":true,\"content_rules\":[]}";
        }
    }

    @SuppressWarnings("unused")
    public static boolean saveReadRules(String rulesJson) {
        File file = new File(READ_CONFIG_FILE);
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            new JSONObject(rulesJson);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(rulesJson);
            }
            sReadLastModified = file.lastModified();
            loadReadRules();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "保存读取规则失败", e);
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static String getReadRulesJson() {
        File file = new File(READ_CONFIG_FILE);
        if (!file.exists()) return getDefaultReadRulesJson();
        try {
            return readFileContent(file);
        } catch (Exception e) {
            return getDefaultReadRulesJson();
        }
    }

    private static String getDefaultReadRulesJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("enabled", false);
            root.put("read_rules", new JSONArray());
            return root.toString(4);
        } catch (Exception e) {
            return "{\"version\":1,\"enabled\":false,\"read_rules\":[]}";
        }
    }

    @SuppressWarnings("unused") // 可能由外部设置
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }
    @SuppressWarnings("unused")
    public static void setReadEnabled(boolean enabled) { sReadEnabled = enabled; }
    @SuppressWarnings("unused")
    public static List<RulePattern> getAllRules() { return new ArrayList<>(sRulePatterns); }
    @SuppressWarnings("unused")
    public static List<ReadRulePattern> getAllReadRules() { return new ArrayList<>(sReadRulePatterns); }
    @SuppressWarnings("unused")
    public static boolean getRulesEmpty() { return sRulePatterns.isEmpty(); }
    @SuppressWarnings("unused")
    public static boolean getReadRulesEmpty() { return sReadRulePatterns.isEmpty(); }
}