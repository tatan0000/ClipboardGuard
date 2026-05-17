package com.android.clipboardguard;

/**
 * 剪贴板权限决策常量。
 * 实际读写持久化由 PermissionProvider 负责；这里仅保留 Hook、UI、弹窗之间共享的决策值。
 */
public class PermissionDecision {

    // ──────────────────────────── 决策值 ────────────────────────────

    public static final int PERMISSION_BLOCK  = 0;
    public static final int PERMISSION_IGNORE = 1;
    public static final int PERMISSION_CLEAR  = 2;
}
