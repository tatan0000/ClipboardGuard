package com.android.clipboardguard;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// ContentRulesManager - 广告内容过滤规则管理器
/// 功能：
/// - 从 JSON 文件加载正则规则（广告链接、推广话术、邀请码等垃圾内容）
/// - 预编译正则 Pattern，缓存到内存
/// - 检查剪贴板文本是否匹配广告规则
/// 使用场景：
/// 某些 App 会自动把广告文案、邀请码、推广链接写入剪贴板，
/// 本规则管理器用于识别这类内容，只对命中的内容弹窗询问用户。
/// 正常文字（如手动复制的文本）不命中规则，直接放行，减少误扰。
/// 存储：/data/data/com.android.clipboardguard/files/content_rules.json
/// 格式：
/// {
///     "version": 1,
///     "enabled": true,
///     "content_rules": [
///         {"name": "短链广告", "regex": "https?://[a-z0-9]{4,10}\\.[a-z]{2,6}/[a-zA-Z0-9]{4,}", "enabled": true},
///         {"name": "邀请码", "regex": "邀请码[：:][A-Za-z0-9]{4,}", "enabled": true}
///     ]
/// }
/// 性能：
/// - 包名判断在正则判断之前，未勾选的 App 不执行正则
/// - 正则只对目标 App 执行，每次只匹配第一个命中规则
/// - 预编译 Pattern，避免每次都重新编译
public class ContentRulesManager {

    private static final String TAG = "ClipboardGuard.ContentRules";

    // 配置文件路径
    private static final String CONFIG_FILE = "/data/data/com.android.clipboardguard/files/content_rules.json";

    // 内存缓存：预编译的正则 Pattern
    private static List<RulePattern> sRulePatterns = new ArrayList<>();

    // 功能开关
    private static volatile boolean sEnabled = true;

    // 缓存控制
    private static long sLastModified = 0;
    private static long sLastLoadTime = 0;
    private static final long CACHE_TTL_MS = 5000; // 5秒缓存

    // 无默认规则：规则完全由用户自定义，未配置时不拦截任何内容

    // ──────────────────────────── 内部类 ────────────────────────────

    /// 单条正则规则
    private static class RulePattern {
        String name;      // 规则名称（用于日志）
        Pattern pattern;  // 预编译的正则
        boolean enabled; // 是否启用

        RulePattern(String name, String regex, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
            try {
                this.pattern = Pattern.compile(regex);
            } catch (Exception e) {
                Log.e(TAG, "正则编译失败: " + regex + " -> " + e.getMessage());
                this.pattern = null;
            }
        }
    }

    // ──────────────────────────── 初始化 ────────────────────────────

    /// 加载正则规则（带缓存）
    /// 启动时调用，后续变更通过广播刷新
    public static synchronized void loadRules() {
        long now = System.currentTimeMillis();
        File file = new File(CONFIG_FILE);

        // 缓存命中检查
        if (sLastModified == file.lastModified()
                && (now - sLastLoadTime) < CACHE_TTL_MS
                && !sRulePatterns.isEmpty()) {
            Log.d(TAG, "正则规则缓存命中，跳过加载");
            return;
        }

        Log.i(TAG, "开始加载正则规则...");
        long start = System.currentTimeMillis();

        if (!file.exists()) {
            Log.i(TAG, "规则文件不存在，规则列表为空（等待用户配置）");
            sRulePatterns.clear();
            sLastModified = 0;
        } else {
            sLastModified = file.lastModified();
            loadRulesFromFile(file);
        }

        sLastLoadTime = System.currentTimeMillis();
        Log.i(TAG, "正则规则加载完成: " + getEnabledRuleCount() + " 条，耗时=" + (sLastLoadTime - start) + "ms");
    }

    /// 从 JSON 文件加载规则
    private static void loadRulesFromFile(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "读取规则文件失败: " + e.getMessage());
            sRulePatterns.clear(); // 读取失败时清空，不拦截任何内容
            return;
        }

        try {
            JSONObject root = new JSONObject(sb.toString());

            // 解析开关
            sEnabled = root.optBoolean("enabled", true);

            // 解析规则列表
            sRulePatterns.clear();
            JSONArray rules = root.optJSONArray("content_rules");
            if (rules != null) {
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject rule = rules.getJSONObject(i);
                    String name = rule.optString("name", "未命名");
                    String regex = rule.optString("regex", "");
                    boolean enabled = rule.optBoolean("enabled", true);

                    if (!regex.isEmpty()) {
                        sRulePatterns.add(new RulePattern(name, regex, enabled));
                    }
                }
            }

            Log.i(TAG, "规则加载完成，共 " + sRulePatterns.size() + " 条");

        } catch (Exception e) {
            Log.e(TAG, "解析规则文件失败: " + e.getMessage() + "，清空规则列表");
            sRulePatterns.clear(); // 解析失败时清空，不拦截任何内容
        }
    }

    // ──────────────────────────── 查询接口 ────────────────────────────

    /// 检查文本是否匹配广告规则
    ///
    /// @param text 剪贴板文本
    /// @return 匹配的规则名称，未匹配或规则为空返回 null（null 表示放行）
    public static String matchesAdContent(String text) {
        if (!sEnabled) {
            return null; // 功能关闭，全部放行
        }

        if (text == null || text.isEmpty()) {
            return null;
        }

        // 规则为空 → 用户未配置任何规则，全部放行
        if (sRulePatterns.isEmpty()) {
            return null;
        }

        // 长度限制，避免卡顿（正则匹配对超长文本很慢）
        if (text.length() > 5000) {
            Log.w(TAG, "文本过长(" + text.length() + ")，跳过正则匹配");
            return null;
        }

        // 遍历所有启用的规则
        for (RulePattern rule : sRulePatterns) {
            if (!rule.enabled || rule.pattern == null) {
                continue;
            }

            try {
                Matcher matcher = rule.pattern.matcher(text);
                if (matcher.find()) {
                    Log.d(TAG, "命中广告规则: " + rule.name + " (regex: " + rule.pattern.pattern() + ")");
                    return rule.name;
                }
            } catch (Exception e) {
                Log.e(TAG, "正则匹配异常: " + rule.name + " -> " + e.getMessage());
            }
        }

        return null;
    }

    /// 检查是否启用
    public static boolean isEnabled() {
        return sEnabled;
    }

    /// 获取已启用的规则数量
    public static int getEnabledRuleCount() {
        int count = 0;
        for (RulePattern rule : sRulePatterns) {
            if (rule.enabled) count++;
        }
        return count;
    }

    /// 获取所有规则（用于 App 端展示）
    public static List<RulePattern> getAllRules() {
        return new ArrayList<>(sRulePatterns);
    }

    /// 规则列表是否为空（用户未配置任何规则）
    public static boolean getRulesEmpty() {
        return sRulePatterns.isEmpty();
    }

    /// 是否已加载
    public static boolean isLoaded() {
        return !sRulePatterns.isEmpty();
    }

    // ──────────────────────────── 持久化（App 端调用）───────────────────────────

    /// 保存规则到 JSON 文件（App 端调用）
    /// @param rules JSON 格式的规则字符串
    public static boolean saveRules(String rulesJson) {
        File file = new File(CONFIG_FILE);
        try {
            // 确保目录存在
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }

            // 验证 JSON 格式
            new JSONObject(rulesJson);

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(rulesJson);
            }

            // 更新缓存
            sLastModified = file.lastModified();
            sLastLoadTime = System.currentTimeMillis();

            // 重新加载到内存
            loadRulesFromFile(file);

            Log.i(TAG, "规则保存成功: " + rulesJson.length() + " 字符");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "保存规则失败: " + e.getMessage());
            return false;
        }
    }

    /// 获取当前规则的 JSON 字符串
    public static String getRulesJson() {
        try {
            File file = new File(CONFIG_FILE);
            if (!file.exists()) {
                return getDefaultRulesJson();
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "读取规则 JSON 失败: " + e.getMessage());
            return getDefaultRulesJson();
        }
    }

    /// 获取空规则模板的 JSON（用于首次展示）
    private static String getDefaultRulesJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("enabled", true);
            root.put("content_rules", new JSONArray()); // 空规则列表
            return root.toString(4);
        } catch (Exception e) {
            return "{\"version\":1,\"enabled\":true,\"content_rules\":[]}";
        }
    }
}
