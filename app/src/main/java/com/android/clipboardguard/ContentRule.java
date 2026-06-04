package com.android.clipboardguard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 读写内容规则的数据模型。 */
public class ContentRule {

    // ──────────────────────────── 持久化字段 ────────────────────────────

    public String name;
    public String pattern;
    public boolean enabled;
    /** 是否为默认规则；默认规则不可删除或编辑正文。 */
    public boolean isDefault;
    /** 规则适配的应用包名列表；为空表示适配所有已勾选拦截的应用。 */
    public List<String> applicablePackages = new ArrayList<>();
    /** 编译后的正则，仅运行时使用，不写入 JSON。 */
    public transient Pattern compiledPattern;

    // ──────────────────────────── 构造与校验 ────────────────────────────

    /** 默认构造函数，用于 JSON 反序列化 */
    public ContentRule() {}

    /** 创建自定义规则（非默认规则） */
    public ContentRule(String name, String pattern, boolean enabled) {
        this.name = name;
        this.pattern = pattern;
        this.enabled = enabled;
        this.isDefault = false;
        compilePattern();
    }

    /** 创建规则，可指定是否为默认规则 */
    public ContentRule(String name, String pattern, boolean enabled, boolean isDefault) {
        this.name = name;
        this.pattern = pattern;
        this.enabled = enabled;
        this.isDefault = isDefault;
        compilePattern();
    }

    /** 编译正则表达式，失败时 compiledPattern 设为 null */
    public void compilePattern() {
        if (pattern == null || pattern.isEmpty()) {
            compiledPattern = null;
            return;
        }
        try {
            compiledPattern = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            compiledPattern = null;
        }
    }

    /**
     * 检测正则是否包含潜在危险模式（可能导致灾难性回溯）。
     * @return null 表示安全，非 null 返回警告信息
     */
    public static String checkDangerousPattern(String regex) {
        if (regex == null || regex.isEmpty()) return null;

        // 检测嵌套量词：(X+)+、(X*)*、(X?)+ 等
        // 这种模式在回溯时会导致指数级复杂度
        if (regex.matches(".*\\([^)]*[+*][^)]*\\)[+*?].*")) {
            return "包含嵌套量词（如 (a+)+），可能导致匹配卡死";
        }

        // 检测量词内的交替嵌套：(a|b)+ 中 a 和 b 有重叠
        // 简化检测：括号内有 | 且外面有量词
        if (regex.matches(".*\\([^)]*\\|[^)]*\\)[+*].*")) {
            return "包含交替嵌套量词（如 (a|b)+），可能存在风险";
        }

        return null;
    }

    // ──────────────────────────── JSON 转换 ────────────────────────────

    /** 将规则序列化为 JSON 对象 */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("name", name);
            json.put("pattern", pattern);
            json.put("enabled", enabled);
            json.put("isDefault", isDefault);
            JSONArray packages = new JSONArray();
            if (applicablePackages != null) {
                for (String packageName : applicablePackages) {
                    if (packageName != null && !packageName.isEmpty()) {
                        packages.put(packageName);
                    }
                }
            }
            json.put("applicable_packages", packages);
        } catch (Exception ignored) {
        }
        return json;
    }

    /** 从 JSON 对象反序列化为规则实例 */
    public static ContentRule fromJson(JSONObject json) {
        ContentRule rule = new ContentRule();
        try {
            rule.name = json.optString("name", "");
            rule.pattern = json.optString("pattern", "");
            rule.enabled = json.optBoolean("enabled", false);
            rule.isDefault = json.optBoolean("isDefault", false);
            JSONArray packages = json.optJSONArray("applicable_packages");
            if (packages != null) {
                for (int i = 0; i < packages.length(); i++) {
                    String packageName = packages.optString(i, "");
                    if (!packageName.isEmpty()) {
                        rule.applicablePackages.add(packageName);
                    }
                }
            }
            rule.compilePattern();
        } catch (Exception ignored) {
        }
        return rule;
    }
}
