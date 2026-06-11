package com.android.clipboardguard;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.Objects;

/**
 * InlineDialogManager - system_server 内弹窗管理器
 *
 * 写入和读取弹窗由 ClipboardHook 同步等待当前选择，超时默认拒绝。
 */
public class InlineDialogManager {

    private static final String TAG = "ClipboardGuard.InlineDialog";

    // 弹窗超时：与 Hook 同步等待时间保持一致。
    private static final long TIMEOUT_MS = ClipboardHook.DIALOG_WAIT_TIMEOUT_MS;
    // 同一应用弹窗防抖（避免同一操作多次弹窗）
    private static final long DIALOG_DEBOUNCE_MS = 3_000;

    // 颜色定义
    private static final int COLOR_PRIMARY = 0xFF007AFF;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_SURFACE_DARK = 0xFF1C1C1E;
    private static final int COLOR_TEXT_PRIMARY = 0xFF1C1C1E;
    private static final int COLOR_TEXT_SECONDARY = 0xFF636366;
    private static final int COLOR_TEXT_HINT = 0xFFAEAEB2;
    private static final int COLOR_BTN_ALLOW = 0xFF007AFF;
    private static final int COLOR_BTN_DENY = 0xFFFF3B30;
    private static final int COLOR_PREVIEW_BG = 0xFFF2F2F7;
    private static final int COLOR_PREVIEW_BG_DARK = 0xFF2C2C2E;
    private static final int COLOR_DIVIDER = 0xFFC6C6C8;
    private static final int COLOR_DIM = 0x80000000;
    private static final int COLOR_DIM_DARK = 0xB0000000;

    @SuppressWarnings("StaticFieldLeak")
    private static InlineDialogManager sInstance;

    private final Context mSystemContext;
    private final Handler mMainHandler;
    private final WindowManager mWindowManager;

    // 当前活跃弹窗
    private View mCurrentDialogView;
    private CountDownTimer mCurrentTimer;
    private String mCurrentPackageName;
    private String mCurrentOperationType;
    private long mLastDialogTime;

    private final Object mLock = new Object();

    // ──────────────────────────── 单例 ────────────────────────────

    private InlineDialogManager(Context context) {
        mSystemContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mWindowManager = (WindowManager) mSystemContext.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 获取单例实例 */
    public static synchronized InlineDialogManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new InlineDialogManager(context);
        }
        return sInstance;
    }

    // ──────────────────────────── 对外入口 ────────────────────────────

    /**
     * 异步显示写入拦截弹窗，ClipboardHook 会等待 notifyWriteDecision() 回传当前选择。
     */
    public void showWriteDialogAsync(String pkgName, String contentPreview) {
        showDialogAsync(pkgName, contentPreview, null, "write", false);
    }

    /**
     * 异步显示读取拦截弹窗（不阻塞 Binder 线程）。
     * ClipboardHook 会用 CountDownLatch 等待 notifyReadDecision() 回传当前选择。
     */
    public void showReadDialogAsync(String pkgName, String contentPreview, String matchedRule) {
        showDialogAsync(pkgName, contentPreview, matchedRule, "read", true);
    }

    // ──────────────────────────── 弹窗创建（fire-and-forget） ────────────────────────────

    private void showDialogAsync(String pkgName, String contentPreview, String matchedRule,
            String operationType, boolean showClearButton) {
        // 同一应用短时间内不重复弹窗
        synchronized (mLock) {
            long now = System.currentTimeMillis();
            if (mCurrentPackageName != null && mCurrentPackageName.equals(pkgName)
                    && now - mLastDialogTime < DIALOG_DEBOUNCE_MS) {
                return;
            }
            mLastDialogTime = now;
        }

        // 如果已有弹窗在显示，先关闭
        dismissCurrentDialog(null, true);

        synchronized (mLock) {
            mCurrentPackageName = pkgName;
            mCurrentOperationType = operationType;
        }

        // 在主线程创建弹窗（非阻塞，post 后立即返回）
        mMainHandler.post(() -> {
            try {
                createAndShowDialog(pkgName, contentPreview, matchedRule, operationType, showClearButton);
            } catch (Throwable e) {
                XLog.e(TAG, "创建弹窗异常: " + e.getMessage());
                notifyDecision(operationType, pkgName, PermissionDecision.PERMISSION_IGNORE);
                dismissCurrentDialog(pkgName, false);
            }
        });
    }

    // ──────────────────────────── 视图构建 ────────────────────────────

    /** 创建并显示拦截弹窗（全代码构建 UI，无 XML 布局） */
    private void createAndShowDialog(String pkgName, String contentPreview, String matchedRule,
            String operationType, boolean showClearButton) {
        synchronized (mLock) {
            if (!Objects.equals(pkgName, mCurrentPackageName)
                    || !Objects.equals(operationType, mCurrentOperationType)) {
                XLog.w(TAG, "忽略过期弹窗创建: " + pkgName);
                return;
            }
        }

        boolean isDarkMode = isDarkMode();

        // 加载应用信息
        String appName = pkgName;
        android.graphics.drawable.Drawable appIcon = null;
        long identity = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mSystemContext.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
            CharSequence label = pm.getApplicationLabel(appInfo);
            appName = label.toString();
            appIcon = pm.getApplicationIcon(pkgName);
        } catch (PackageManager.NameNotFoundException e) {
            XLog.w(TAG, "应用不存在: " + pkgName);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        // 创建弹窗根布局
        FrameLayout rootLayout = new FrameLayout(mSystemContext);
        rootLayout.setBackgroundColor(isDarkMode ? COLOR_DIM_DARK : COLOR_DIM);

        // 创建卡片
        LinearLayout card = createDialogCard(pkgName, appName, appIcon, contentPreview,
                operationType, showClearButton, isDarkMode);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dpToPx(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        rootLayout.addView(card, cardParams);

        LayoutParams windowParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.CENTER;

        try {
            mWindowManager.addView(rootLayout, windowParams);
            boolean stillCurrent;
            synchronized (mLock) {
                stillCurrent = Objects.equals(pkgName, mCurrentPackageName)
                        && Objects.equals(operationType, mCurrentOperationType);
                if (stillCurrent) {
                    mCurrentDialogView = rootLayout;
                }
            }
            if (!stillCurrent) {
                try {
                    mWindowManager.removeView(rootLayout);
                } catch (Throwable ignored) {}
                XLog.w(TAG, "弹窗添加后已过期，已移除: " + pkgName);
                return;
            }
            startCountdown(pkgName);
            XLog.i(TAG, "弹窗已显示: " + pkgName + (matchedRule != null ? " [匹配: " + matchedRule + "]" : ""));
        } catch (Exception e) {
            XLog.e(TAG, "添加弹窗失败: " + e.getMessage());
            notifyDecision(operationType, pkgName, PermissionDecision.PERMISSION_IGNORE);
            dismissCurrentDialog(pkgName, false);
        }
    }

    private LinearLayout createDialogCard(String pkgName, String appName,
            android.graphics.drawable.Drawable appIcon, String contentPreview,
            String operationText, boolean showClearButton, boolean isDarkMode) {

        LinearLayout card = new LinearLayout(mSystemContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(24), dpToPx(32), dpToPx(24), 0);
        card.setBackground(createCardBackground(isDarkMode));

        // 应用图标
        ImageView ivIcon = new ImageView(mSystemContext);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(72), dpToPx(72));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        ivIcon.setLayoutParams(iconParams);
        if (appIcon != null) {
            ivIcon.setImageDrawable(appIcon);
        } else {
            ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        card.addView(ivIcon);

        // 应用名称
        TextView tvAppName = new TextView(mSystemContext);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.gravity = Gravity.CENTER_HORIZONTAL;
        nameParams.topMargin = dpToPx(16);
        tvAppName.setLayoutParams(nameParams);
        tvAppName.setText(appName);
        tvAppName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvAppName.setTextColor(isDarkMode ? Color.WHITE : COLOR_TEXT_PRIMARY);
        tvAppName.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tvAppName);

        // 倒计时文字
        TextView tvCountdown = new TextView(mSystemContext);
        LinearLayout.LayoutParams countdownParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countdownParams.gravity = Gravity.CENTER_HORIZONTAL;
        countdownParams.topMargin = dpToPx(6);
        tvCountdown.setLayoutParams(countdownParams);
        tvCountdown.setText(String.format(Locale.ROOT, "%d 秒后自动拒绝", TIMEOUT_MS / 1000));
        tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvCountdown.setTextColor(COLOR_PRIMARY);
        tvCountdown.setTag("countdown");
        card.addView(tvCountdown);

        // 权限说明
        TextView tvMessage = new TextView(mSystemContext);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgParams.topMargin = dpToPx(16);
        tvMessage.setLayoutParams(msgParams);
        tvMessage.setText(String.format(Locale.ROOT, "%s 正在%s剪贴板", appName,
                "read".equals(operationText) ? "读取" : "写入"));
        tvMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvMessage.setTextColor(isDarkMode ? 0xFFAAAAAA : COLOR_TEXT_SECONDARY);
        tvMessage.setGravity(Gravity.CENTER);
        tvMessage.setLineSpacing(dpToPx(4), 1.0f);
        card.addView(tvMessage);

        // 内容预览
        if (contentPreview != null && !contentPreview.isEmpty()) {
            TextView tvPreview = new TextView(mSystemContext);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            previewParams.topMargin = dpToPx(12);
            tvPreview.setLayoutParams(previewParams);
            tvPreview.setText(contentPreview);
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvPreview.setTextColor(isDarkMode ? 0xFFBBBBBB : COLOR_TEXT_SECONDARY);
            tvPreview.setMaxLines(3);
            tvPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvPreview.setBackground(createPreviewBackground(isDarkMode));
            tvPreview.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
            card.addView(tvPreview);
        }

        // 分隔线
        View divider = new View(mSystemContext);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        dividerParams.topMargin = dpToPx(20);
        divider.setLayoutParams(dividerParams);
        divider.setBackgroundColor(COLOR_DIVIDER);
        card.addView(divider);

        LinearLayout btnContainer = new LinearLayout(mSystemContext);
        LinearLayout.LayoutParams btnContainerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                showClearButton ? dpToPx(158) : dpToPx(56));
        btnContainer.setLayoutParams(btnContainerParams);
        btnContainer.setOrientation(showClearButton ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        btnContainer.setBackgroundColor(Color.TRANSPARENT);

        if (showClearButton) {
            addReadDialogButton(btnContainer, pkgName, "允许", PermissionDecision.PERMISSION_IGNORE, COLOR_BTN_ALLOW, true);
            addDialogDivider(btnContainer, true);
            addReadDialogButton(btnContainer, pkgName, "拒绝", PermissionDecision.PERMISSION_BLOCK, COLOR_BTN_DENY, false);
            addDialogDivider(btnContainer, true);
            addReadDialogButton(btnContainer, pkgName, "拒绝并清空", PermissionDecision.PERMISSION_CLEAR, 0xFFFF3B30, false);
        } else {
            addWriteDialogButton(btnContainer, pkgName, "拒绝", PermissionDecision.PERMISSION_BLOCK, COLOR_BTN_DENY, false);
            addDialogDivider(btnContainer, false);
            addWriteDialogButton(btnContainer, pkgName, "允许", PermissionDecision.PERMISSION_IGNORE, COLOR_BTN_ALLOW, true);
        }
        card.addView(btnContainer);

        return card;
    }

    // ──────────────────────────── 按钮 ────────────────────────────

    private void addWriteDialogButton(LinearLayout btnContainer, String pkgName, String text, int decision,
            int textColor, boolean isAllow) {
        addDialogButton(btnContainer, pkgName, text, decision, textColor, isAllow,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
    }

    private void addReadDialogButton(LinearLayout btnContainer, String pkgName, String text, int decision,
            int textColor, boolean isAllow) {
        addDialogButton(btnContainer, pkgName, text, decision, textColor, isAllow,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(52)));
    }

    private void addDialogButton(LinearLayout btnContainer, String pkgName, String text, int decision,
            int textColor, boolean isAllow, LinearLayout.LayoutParams params) {
        TextView button = new TextView(mSystemContext);
        button.setLayoutParams(params);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTextColor(textColor);
        if (isAllow) button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(createButtonBackground(isAllow));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> onResult(pkgName, decision));
        btnContainer.addView(button);
    }

    private void addDialogDivider(LinearLayout btnContainer, boolean horizontal) {
        View divider = new View(mSystemContext);
        LinearLayout.LayoutParams params = horizontal
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1))
                : new LinearLayout.LayoutParams(dpToPx(1), ViewGroup.LayoutParams.MATCH_PARENT);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(COLOR_DIVIDER);
        btnContainer.addView(divider);
    }

    // ──────────────────────────── 样式工具 ────────────────────────────

    private GradientDrawable createCardBackground(boolean isDarkMode) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dpToPx(28));
        drawable.setColor(isDarkMode ? COLOR_SURFACE_DARK : COLOR_SURFACE);
        return drawable;
    }

    private GradientDrawable createPreviewBackground(boolean isDarkMode) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dpToPx(8));
        drawable.setColor(isDarkMode ? COLOR_PREVIEW_BG_DARK : COLOR_PREVIEW_BG);
        return drawable;
    }

    private GradientDrawable createButtonBackground(boolean isAllow) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        float[] radii;
        if (isAllow) {
            radii = new float[]{0, 0, dpToPx(24), dpToPx(24), 0, 0, 0, 0};
        } else {
            radii = new float[]{0, 0, 0, 0, dpToPx(24), dpToPx(24), 0, 0};
        }
        drawable.setCornerRadii(radii);
        return drawable;
    }

    // ──────────────────────────── 倒计时 ────────────────────────────

    private void startCountdown(String pkgName) {
        if (mCurrentTimer != null) {
            mCurrentTimer.cancel();
        }
        mCurrentTimer = new CountDownTimer(TIMEOUT_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) (millisUntilFinished / 1000);
                mMainHandler.post(() -> updateCountdown(seconds));
            }

            @Override
            public void onFinish() {
                XLog.i(TAG, "弹窗超时自动拒绝: " + pkgName);
                onResult(pkgName, PermissionDecision.PERMISSION_BLOCK);
            }
        };
        mCurrentTimer.start();
    }

    private void updateCountdown(int seconds) {
        if (mCurrentDialogView != null) {
            TextView tvCountdown = mCurrentDialogView.findViewWithTag("countdown");
            if (tvCountdown != null) {
                tvCountdown.setText(String.format(Locale.ROOT, "%d 秒后自动拒绝", seconds));
            }
        }
    }

    // ──────────────────────────── 结果处理（非阻塞） ────────────────────────────

    /**
     * 用户点击按钮或超时。
     * 将决策写入 ClipboardHook 防抖缓存，使下一次同一应用操作在防抖窗口内使用此决策。
     */
    private void onResult(String pkgName, int decision) {
        if (!Objects.equals(pkgName, mCurrentPackageName)) {
            XLog.w(TAG, "忽略过期结果: " + pkgName + " (当前: " + mCurrentPackageName + ")");
            return;
        }

        // 取消倒计时
        if (mCurrentTimer != null) {
            mCurrentTimer.cancel();
            mCurrentTimer = null;
        }

        // 回写到 ClipboardHook 防抖缓存（用于后续操作）
        // 同步唤醒等待中的 Hook，使当前剪贴板操作能拿到用户真实选择。
        notifyDecision(mCurrentOperationType, pkgName, decision);

        XLog.i(TAG, "用户选择: " + pkgName + " -> "
                + (decision == PermissionDecision.PERMISSION_IGNORE ? "允许"
                        : decision == PermissionDecision.PERMISSION_CLEAR ? "拒绝并清空" : "拒绝"));

        // 关闭弹窗
        dismissCurrentDialog(pkgName, false);
    }

    // ──────────────────────────── 弹窗清理 ────────────────────────────

    /** 关闭当前弹窗，可选是否通知 Hook 侧阻止操作 */
    private void dismissCurrentDialog(String expectedPkgName, boolean notifyBlocked) {
        if (expectedPkgName != null && !Objects.equals(expectedPkgName, mCurrentPackageName)) {
            return;
        }

        CountDownTimer timerToCancel;
        View viewToRemove;
        String pkgToNotify;
        String operationToNotify;

        synchronized (mLock) {
            timerToCancel = mCurrentTimer;
            viewToRemove = mCurrentDialogView;
            pkgToNotify = mCurrentPackageName;
            operationToNotify = mCurrentOperationType;
            mCurrentTimer = null;
            mCurrentDialogView = null;
            mCurrentPackageName = null;
            mCurrentOperationType = null;
        }

        if (notifyBlocked && pkgToNotify != null) {
            notifyDecision(operationToNotify, pkgToNotify, PermissionDecision.PERMISSION_BLOCK);
        }

        if (timerToCancel != null) {
            try {
                timerToCancel.cancel();
            } catch (Throwable ignored) {}
        }

        if (viewToRemove != null && mWindowManager != null) {
            try {
                mMainHandler.post(() -> {
                    try {
                        mWindowManager.removeView(viewToRemove);
                    } catch (IllegalArgumentException e) {
                        // 视图已被移除
                    } catch (Throwable e) {
                        XLog.w(TAG, "移除弹窗失败: " + e.getMessage());
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    /** 通知 Hook 侧用户决策，写入防抖缓存并唤醒等待线程 */
    private void notifyDecision(String operationType, String pkgName, int decision) {
        if (pkgName == null) return;
        try {
            if ("write".equals(operationType)) {
                ClipboardHook.cacheWriteDecision(pkgName, decision);
                ClipboardHook.notifyWriteDecision(pkgName, decision);
            } else {
                ClipboardHook.cacheReadDecision(pkgName, decision);
                ClipboardHook.notifyReadDecision(pkgName, decision);
            }
        } catch (Throwable e) {
            XLog.w(TAG, "回写防抖缓存失败: " + e.getMessage());
        }
    }

    // ──────────────────────────── 通用工具 ────────────────────────────

    /** dp 转 px */
    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                mSystemContext.getResources().getDisplayMetrics());
    }

    /** 检查当前是否为深色模式 */
    private boolean isDarkMode() {
        int nightModeFlags = mSystemContext.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
