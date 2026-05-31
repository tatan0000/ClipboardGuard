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
        public List<String> applicablePackages;
        WriteRulePattern(String name, String regex, boolean enabled, List<String> applicablePackages) {
            this.name = name;
            this.enabled = enabled;
            this.applicablePackages = applicablePackages != null
                    ? new ArrayList<>(applicablePackages)
                    : new ArrayList<>();
            try {
                this.pattern = java.util.regex.Pattern.compile(regex);
            } catch (Exception e) {
                XLog.e(TAG, "正则编译失败: " + regex + " -> " + e.getMessage());
                this.pattern = null;
            }
        }

        boolean appliesToPackage(String packageName) {
            return ContentRulesManager.appliesToPackage(applicablePackages, packageName);
        }
    }

    public static class ReadRulePattern {
        public String name;
        public java.util.regex.Pattern pattern;
        public boolean enabled;
        public List<String> applicablePackages;
        ReadRulePattern(String name, String regex, boolean enabled, List<String> applicablePackages) {
            this.name = name;
            this.enabled = enabled;
            this.applicablePackages = applicablePackages != null
                    ? new ArrayList<>(applicablePackages)
                    : new ArrayList<>();
            try {
                this.pattern = java.util.regex.Pattern.compile(regex);
            } catch (Exception e) {
                XLog.e(TAG, "正则编译失败: " + regex + " -> " + e.getMessage());
                this.pattern = null;
            }
        }

        boolean appliesToPackage(String packageName) {
            return ContentRulesManager.appliesToPackage(applicablePackages, packageName);
        }
    }

    // ──────────────────────────── 写入规则查询 ────────────────────────────

    /**
     * 检查文本是否匹配写入内容规则。
     * @return 匹配的规则名称，未匹配返回 null
     */
    public static synchronized String matchesWriteContent(String packageName, String text) {
        if (!sWriteEnabled) return null;
        if (packageName == null || packageName.isEmpty()) return null;
        if (text == null || text.isEmpty()) return null;
        if (sWriteRulePatterns.isEmpty()) return null;
        if (text.length() > 5000) return null;

        for (WriteRulePattern rule : sWriteRulePatterns) {
            if (!rule.enabled || rule.pattern == null) continue;
            if (!rule.appliesToPackage(packageName)) continue;
            try {
                if (rule.pattern.matcher(text).find()) return rule.name;
            } catch (Exception e) {
                XLog.e(TAG, "正则匹配异常: " + rule.name);
            }
        }
        return null;
    }

    public static synchronized boolean isWriteEnabled() { return sWriteEnabled; }
    public static synchronized int getWriteEnabledRuleCount() {
        int cnt = 0;
        for (WriteRulePattern r : sWriteRulePatterns) if (r.enabled) cnt++;
        return cnt;
    }

    public static synchronized boolean hasEnabledWriteRule() {
        for (WriteRulePattern r : sWriteRulePatterns) if (r.enabled && r.pattern != null) return true;
        return false;
    }

    public static synchronized boolean isWriteLoaded() { return sWriteLoaded; }

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
                            sReadRulePatterns.add(new ReadRulePattern(
                                    name,
                                    regex,
                                    r.optBoolean("enabled", true),
                                    parseApplicablePackages(r)
                            ));
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
                            sWriteRulePatterns.add(new WriteRulePattern(
                                    name,
                                    regex,
                                    r.optBoolean("enabled", true),
                                    parseApplicablePackages(r)
                            ));
                        }
                    }
                }
                sWriteLoaded = true;
            }
            String type = isReadRules ? "读取" : "写入";
            boolean enabled = isReadRules ? sReadEnabled : sWriteEnabled;
            if (!enabled) {
                XLog.d(TAG, type + "规则通过广播更新完成: 规则未启用");
            } else {
                XLog.d(TAG, type + "规则通过广播更新完成: " + buildRuleSummary(isReadRules));
            }
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

    /**
     * 合并自定义规则文件与默认规则文件，供 Hook 运行时匹配。
     * customRulesJson：write_rules.json / read_rules.json（含 enabled + content_rules）
     * defaultRulesJson：write_default_rules.json / read_default_rules.json（JSONArray，只合并 enabled 项）
     */
    public static String mergeRulesForRuntime(String customRulesJson, String defaultRulesJson) {
        try {
            JSONObject mergedRoot = new JSONObject();
            JSONArray mergedArr = new JSONArray();
            boolean enabled = false;

            if (customRulesJson != null && !customRulesJson.isEmpty()) {
                try {
                    JSONObject root = new JSONObject(customRulesJson);
                    enabled = root.optBoolean("enabled", false);
                    JSONArray arr = root.optJSONArray("content_rules");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            mergedArr.put(arr.getJSONObject(i));
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (defaultRulesJson != null && !defaultRulesJson.isEmpty()) {
                try {
                    JSONArray defaults = new JSONArray(defaultRulesJson);
                    for (int i = 0; i < defaults.length(); i++) {
                        JSONObject rule = defaults.getJSONObject(i);
                        if (rule.optBoolean("enabled", false)) {
                            mergedArr.put(rule);
                        }
                    }
                } catch (Exception ignored) {}
            }

            mergedRoot.put("enabled", enabled);
            mergedRoot.put("content_rules", mergedArr);
            return mergedRoot.toString();
        } catch (Exception e) {
            XLog.e(TAG, "mergeRulesForRuntime 失败: " + e.getMessage());
            return "{\"enabled\":false,\"content_rules\":[]}";
        }
    }

    // ──────────────────────────── 读取规则查询 ────────────────────────────

    public static synchronized String matchesReadContent(String packageName, String text) {
        if (!sReadEnabled) return null;
        if (packageName == null || packageName.isEmpty()) return null;
        if (text == null || text.isEmpty()) return null;
        if (sReadRulePatterns.isEmpty()) return null;
        if (text.length() > 5000) return null;
        for (ReadRulePattern rule : sReadRulePatterns) {
            if (!rule.enabled || rule.pattern == null) continue;
            if (!rule.appliesToPackage(packageName)) continue;
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

    public static synchronized boolean isReadEnabled() { return sReadEnabled; }

    public static synchronized int getReadEnabledRuleCount() {
        int cnt = 0;
        for (ReadRulePattern r : sReadRulePatterns) if (r.enabled) cnt++;
        return cnt;
    }

    public static synchronized boolean hasEnabledReadRule() {
        for (ReadRulePattern r : sReadRulePatterns) if (r.enabled && r.pattern != null) return true;
        return false;
    }

    public static synchronized boolean isReadLoaded() { return sReadLoaded; }

    /** 生成规则摘要日志：名称 + 拦截包列表 + 条数。 */
    private static String buildRuleSummary(boolean isRead) {
        List<?> rules = isRead
                ? new ArrayList<>(sReadRulePatterns)
                : new ArrayList<>(sWriteRulePatterns);
        if (rules.isEmpty()) return "无规则";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Object obj : rules) {
            if (isRead) {
                ReadRulePattern r = (ReadRulePattern) obj;
                if (r.enabled && r.pattern != null) {
                    sb.append('[').append(r.name);
                    if (r.applicablePackages != null && !r.applicablePackages.isEmpty()) {
                        sb.append(" => ").append(r.applicablePackages);
                    }
                    sb.append("] ");
                    count++;
                }
            } else {
                WriteRulePattern r = (WriteRulePattern) obj;
                if (r.enabled && r.pattern != null) {
                    sb.append('[').append(r.name);
                    if (r.applicablePackages != null && !r.applicablePackages.isEmpty()) {
                        sb.append(" => ").append(r.applicablePackages);
                    }
                    sb.append("] ");
                    count++;
                }
            }
        }
        if (count == 0) return "0 条启用";
        sb.append("(共").append(count).append("条启用)");
        return sb.toString();
    }

    private static List<String> parseApplicablePackages(JSONObject ruleJson) {
        List<String> packages = new ArrayList<>();
        JSONArray packageArray = ruleJson.optJSONArray("applicable_packages");
        if (packageArray == null) return packages;
        for (int index = 0; index < packageArray.length(); index++) {
            String packageName = packageArray.optString(index, "");
            if (!packageName.isEmpty()) {
                packages.add(packageName);
            }
        }
        return packages;
    }

    private static boolean appliesToPackage(List<String> applicablePackages, String packageName) {
        return applicablePackages == null || applicablePackages.isEmpty()
                || applicablePackages.contains(packageName);
    }
}
