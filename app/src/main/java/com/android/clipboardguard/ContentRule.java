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

    public ContentRule() {}

    public ContentRule(String name, String pattern, boolean enabled) {
        this.name = name;
        this.pattern = pattern;
        this.enabled = enabled;
        this.isDefault = false;
        compilePattern();
    }

    public ContentRule(String name, String pattern, boolean enabled, boolean isDefault) {
        this.name = name;
        this.pattern = pattern;
        this.enabled = enabled;
        this.isDefault = isDefault;
        compilePattern();
    }

    public boolean appliesToPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (applicablePackages == null || applicablePackages.isEmpty()) return true;
        return applicablePackages.contains(packageName);
    }

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

    // ──────────────────────────── JSON 转换 ────────────────────────────

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
