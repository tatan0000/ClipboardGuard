package com.android.clipboardguard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ContentRulesManager - 剪贴板内容规则管理器（写入 + 读取）
 * 运行说明：
 * - 该类在 Hook 侧维护已编译的读取/写入正则缓存。
 * - system_server 不能直接读取 App 私有目录，规则文件由 App 端维护。
 * - loadWriteRules / loadReadRules 只标记初始化完成，实际规则通过广播 JSON 同步。
 * - 读取规则和写入规则保持独立开关、独立缓存，避免两类配置互相影响。
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

    // ──────────────────────────── 初始化占位 ────────────────────────────

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

    // ──────────────────────────── 写入规则查询 ────────────────────────────

    /**
     * 检查文本是否匹配写入内容规则。
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

    // ──────────────────────────── 广播规则更新 ────────────────────────────

    /**
     * 使用广播 JSON 完全替换读取或写入规则缓存。
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

    public static synchronized void updateWriteRulesFromJson(String json) {
        updateReadWriteRulesFromJson(json, false);
    }

    public static synchronized void updateReadRulesFromJson(String json) {
        updateReadWriteRulesFromJson(json, true);
    }

    // ──────────────────────────── 读取规则查询 ────────────────────────────

    public static String matchesReadContent(String text) {
        if (!sReadEnabled) return null;
        if (text == null || text.isEmpty()) return null;
        if (sReadRulePatterns.isEmpty()) return null;
        if (text.length() > 5000) return null;
        for (ReadRulePattern rule : sReadRulePatterns) {
            if (!rule.enabled || rule.pattern == null) continue;
            try {
                // 银行卡号容易和快递单号、订单号重叠，正则命中后再用 Luhn 做二次确认。
                if ("银行卡号".equals(rule.name)) {
                    if (matchesBankCardContent(rule, text)) return rule.name;
                    continue;
                }
                if (rule.pattern.matcher(text).find()) return rule.name;
            } catch (Exception e) {
                XLog.e(TAG, "正则匹配异常: " + rule.name);
            }
        }
        return null;
    }

    private static boolean matchesBankCardContent(ReadRulePattern rule, String text) {
        java.util.regex.Matcher matcher = rule.pattern.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            // 允许用户复制带空格或短横分组的卡号，校验前统一还原为纯数字。
            String digits = candidate.replace(" ", "").replace("-", "");
            if (digits.length() < 13 || digits.length() > 19) continue;
            if (isLuhnValid(digits)) return true;
        }
        return false;
    }

    private static boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        // 从右向左按 Luhn 规则累加，最后一位为校验位。
        for (int index = digits.length() - 1; index >= 0; index--) {
            char ch = digits.charAt(index);
            if (ch < '0' || ch > '9') return false;
            int value = ch - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) value -= 9;
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    public static boolean isReadEnabled() { return sReadEnabled; }

    public static int getReadEnabledRuleCount() {
        int cnt = 0;
        for (ReadRulePattern r : sReadRulePatterns) if (r.enabled) cnt++;
        return cnt;
    }

    public static boolean hasEnabledReadRule() {
        for (ReadRulePattern r : sReadRulePatterns) if (r.enabled && r.pattern != null) return true;
        return false;
    }

    public static boolean isReadLoaded() { return sReadLoaded; }

}
