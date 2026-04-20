package com.android.clipboardguard;

/**
 * 日志条目数据模型
 */
public class LogEntry {
    public String packageName;
    public String action;      // "拦截" / "放行"
    public String content;     // 剪贴板内容预览
    public long timestamp;

    public LogEntry(String packageName, String action, String content, long timestamp) {
        this.packageName = packageName;
        this.action = action;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getTimeStr() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestamp));
    }

    public String getDateStr() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestamp));
    }
}
