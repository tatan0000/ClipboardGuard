package com.android.clipboardguard;

public final class PrivacyLogUtils {

    private PrivacyLogUtils() {}

    public static String maskClipboardContent(String content) {
        if (content == null || content.isEmpty()) return content;
        int visibleCount = Math.max(1, content.length() / 2);
        StringBuilder builder = new StringBuilder(content.length());
        int contentCount = 0;
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (contentCount < visibleCount || Character.isWhitespace(ch)) {
                builder.append(ch);
            } else {
                builder.append('*');
            }
            if (!Character.isWhitespace(ch)) {
                contentCount++;
            }
        }
        return builder.toString();
    }
}
