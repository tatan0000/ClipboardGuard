package com.android.clipboardguard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/*
 * ContentRulesManager - 广告内容过滤规则管理器（写入 + 读取）
 *
 * 改造说明（2026-05-05）：
 * - 移除所有硬编码文件路径，system_server 进程无法访问 App 私有目录
 * - loadWriteRules / loadReadRules 改为空操作（sLoaded=true），实际数据由广播推送
 * - App 端通过 Activity.getFilesDir() 直接操作文件，不经过此类
 * - 所有规则数据仅通过广播中的 JSON 字符串更新（updateWriteRulesFromJson / updateReadRulesFromJson）
 */
public class ContentRulesManager {

    private static final String TAG = "ClipboardGuard.ContentRules";

    // ── 写入规则缓存 ──
    private static final List<WriteRulePattern> sWriteRulePatterns = new ArrayList<>();
    private static volatile boolean sWriteEnabled = true;
    private static volatile boolean sWriteLoaded = false;

    // ── 读取规则缓存 ──
    private static final List<ReadRulePattern> sReadRulePatterns = new ArrayList<>();
    private static volatile boolean sReadEnabled = false;
    private static volatile boolean sReadLoaded = false;

    // ──────────────────────────── 数据类 ────────────────────────────

    public static class WriteRulePattern {
        public String name;
        public java.util.regex.Pattern pattern;
        public boolean enabled;
        WriteRulePattern(String name, String regex, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
            try {
                this.pattern = java.util.regex.Pattern.compile(regex);
            } catch (Exception e) {
                XLog.e(TAG, "正则编译失败: " + regex + " -> " + e.getMessage());
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
                XLog.e(TAG, "正则编译失败: " + regex + " -> " + e.getMessage());
                this.pattern = null;
            }
        }
    }

    // ──────────────────────────── 加载方法（system_server 中为空操作） ────────────────────────────

    /**
     * 加载写入规则。
     * 在 system_server 进程中无法直接读取 App 私有目录的文件，
     * 所以这里只标记为已加载（sLoaded=true），实际数据通过广播推送。
     */
    public static synchronized void loadWriteRules() {
        sWriteLoaded = true;
        XLog.i(TAG, "loadWriteRules: system_server 中跳过文件读取，等待广播推送规则数据");
    }

    /**
     * 加载读取规则。
     * 在 system_server 进程中无法直接读取 App 私有目录的文件，
     * 所以这里只标记为已加载（sReadLoaded=true），实际数据通过广播推送。
     */
    public static synchronized void loadReadRules() {
        sReadLoaded = true;
        XLog.i(TAG, "loadReadRules: system_server 中跳过文件读取，等待广播推送规则数据");
    }

    // ──────────────────────────── 查询接口 ────────────────────────────

    /**
     * 检查文本是否匹配广告规则
     * @return 匹配的规则名称，未匹配返回 null
     */
    public static String matchesWriteContent(String text) {
        if (!sWriteEnabled) return null;
        if (text == null || text.isEmpty()) return null;
        if (sWriteRulePatterns.isEmpty()) return null;
        if (text.length() > 5000) return null;

        for (WriteRulePattern rule : sWriteRulePatterns) {
            if (!rule.enabled || rule.pattern == null) continue;
            try {
                if (rule.pattern.matcher(text).find()) return rule.name;
            } catch (Exception e) {
                XLog.e(TAG, "正则匹配异常: " + rule.name);
            }
        }
        return null;
    }

    public static boolean isWriteEnabled() { return sWriteEnabled; }
    public static int getWriteEnabledRuleCount() {
        int cnt = 0;
        for (WriteRulePattern r : sWriteRulePatterns) if (r.enabled) cnt++;
        return cnt;
    }

    public static boolean hasEnabledWriteRule() {
        for (WriteRulePattern r : sWriteRulePatterns) if (r.enabled && r.pattern != null) return true;
        return false;
    }

    public static boolean isWriteLoaded() { return sWriteLoaded; }

    // ──────────────────────────── 广播更新接口 ────────────────────────────

    /**
     * 从广播 JSON 完全替换规则列表
     */
    public static synchronized void updateReadWriteRulesFromJson(String json, boolean isReadRules) {
        if (json == null || json.isEmpty()) {
            if (isReadRules) {
                sReadRulePatterns.clear();
                sReadEnabled = false;
                sReadLoaded = true;
            } else {
                sWriteRulePatterns.clear();
                sWriteEnabled = false;
                sWriteLoaded = true;
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
                sWriteRulePatterns.clear();
                sWriteEnabled = root.optBoolean("enabled", true);
                JSONArray arr = root.optJSONArray("content_rules");
                if (arr == null) arr = root.optJSONArray("rules");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.getJSONObject(i);
                        String name = r.optString("name", "");
                        String regex = r.optString("pattern", "");
                        if (regex.isEmpty()) regex = r.optString("regex", "");
                        if (!regex.isEmpty()) {
                            sWriteRulePatterns.add(new WriteRulePattern(name, regex, r.optBoolean("enabled", true)));
                        }
                    }
                }
                sWriteLoaded = true;
            }
            XLog.d(TAG, (isReadRules ? "读取" : "写入") + "规则通过广播更新完成: "
                    + (isReadRules ? getReadEnabledRuleCount() : getWriteEnabledRuleCount()) + " 条启用");
        } catch (Exception e) {
            XLog.e(TAG, "解析规则 JSON 失败", e);
            if (isReadRules) {
                sReadRulePatterns.clear();
                sReadEnabled = false;
                sReadLoaded = true;
            } else {
                sWriteRulePatterns.clear();
                sWriteEnabled = false;
                sWriteLoaded = true;
            }
        }
    }

    @SuppressWarnings("unused") // 兼容旧调用
    public static synchronized void updateWriteRulesFromJson(String json) {
        updateReadWriteRulesFromJson(json, false);
    }

    public static synchronized void updateReadRulesFromJson(String json) {
        updateReadWriteRulesFromJson(json, true);
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
                XLog.e(TAG, "正则匹配异常: " + rule.name);
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

    @SuppressWarnings("unused") // 可能由外部设置
    public static void setWriteEnabled(boolean enabled) { sWriteEnabled = enabled; }
    @SuppressWarnings("unused")
    public static void setReadEnabled(boolean enabled) { sReadEnabled = enabled; }
    @SuppressWarnings("unused")
    public static List<WriteRulePattern> getAllWriteRules() { return new ArrayList<>(sWriteRulePatterns); }
    @SuppressWarnings("unused")
    public static List<ReadRulePattern> getAllReadRules() { return new ArrayList<>(sReadRulePatterns); }
    @SuppressWarnings("unused")
    public static boolean getWriteRulesEmpty() { return sWriteRulePatterns.isEmpty(); }
    @SuppressWarnings("unused")
    public static boolean getReadRulesEmpty() { return sReadRulePatterns.isEmpty(); }
}
