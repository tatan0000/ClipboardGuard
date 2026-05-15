package com.android.clipboardguard;

import org.json.JSONObject;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则数据模型
 */
public class ContentRule {
    public String name;
    public String pattern;
    public boolean enabled;
    public boolean isDefault; // 是否为默认规则（不可删除/修改）
    public transient Pattern compiledPattern; // 编译后的正则（transient 不参与 JSON 序列化）

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

    public void compilePattern() {
        try {
            compiledPattern = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            compiledPattern = null;
        }
    }

    public boolean isValid() {
        return compiledPattern != null;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("name", name);
            json.put("pattern", pattern);
            json.put("enabled", enabled);
            json.put("isDefault", isDefault);
        } catch (Exception ignored) {}
        return json;
    }

    public static ContentRule fromJson(JSONObject json) {
        ContentRule rule = new ContentRule();
        try {
            rule.name = json.optString("name", "");
            rule.pattern = json.optString("pattern", "");
            rule.enabled = json.optBoolean("enabled", false);
            rule.isDefault = json.optBoolean("isDefault", false);
            rule.compilePattern();
        } catch (Exception ignored) {}
        return rule;
    }
}
