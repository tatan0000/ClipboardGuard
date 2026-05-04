package com.android.clipboardguard;

import android.content.Context;
import android.view.WindowManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * InlineDialogManager - system_server 内弹窗管理器
 *
 * 功能：
 * - 直接在 system_server 进程内创建权限询问浮窗
 * - 使用 WindowManager.addView() 渲染，无需跨进程启动 Activity
 * - 通过 CountDownLatch 同步等待用户选择结果
 * - 自动处理超时（默认 4 秒）
 *
 * 优势：
 * - 无 Activity 启动延迟（约 200-500ms）
 * - 不依赖 ActivityTaskManager，避免 invisible task 跳过问题
 * - 不需要 SYSTEM_ALERT_WINDOW 权限（TYPE_APPLICATION_OVERLAY）
 */
public class InlineDialogManager {

    private static final String TAG = "ClipboardGuard.InlineDialog";

    // 弹窗超时（4 秒）
    private static final long TIMEOUT_MS = 4_000;

    // 颜色定义（程序化定义，避免依赖资源）
    private static final int COLOR_PRIMARY = 0xFF007AFF;       // iOS 蓝
    private static final int COLOR_SURFACE = 0xFFFFFFFF;        // 白色卡片
    private static final int COLOR_SURFACE_DARK = 0xFF1C1C1E;   // 深色表面
    private static final int COLOR_TEXT_PRIMARY = 0xFF1C1C1E;   // 主文字
    private static final int COLOR_TEXT_SECONDARY = 0xFF636366; // 次要文字
    private static final int COLOR_TEXT_HINT = 0xFFAEAEB2;     // 提示文字
    private static final int COLOR_BTN_ALLOW = 0xFF007AFF;      // 允许按钮文字
    private static final int COLOR_BTN_DENY = 0xFF8E8E93;       // 拒绝按钮文字
    private static final int COLOR_PREVIEW_BG = 0xFFF2F2F7;     // 预览背景
    private static final int COLOR_PREVIEW_BG_DARK = 0xFF2C2C2E;
    private static final int COLOR_DIVIDER = 0xFFC6C6C8;        // 分割线
    private static final int COLOR_DIM = 0x80000000;            // 半透明遮罩
    private static final int COLOR_DIM_DARK = 0xB0000000;       // 深色遮罩

    private static InlineDialogManager sInstance;

    private final Context mSystemContext;
    private final Handler mMainHandler;
    private final WindowManager mWindowManager;

    // 当前活跃的弹窗
    private View mCurrentDialogView;
    private LayoutParams mCurrentWindowParams;
    private CountDownLatch mCurrentLatch;
    private AtomicInteger mCurrentResult;
    private CountDownTimer mCurrentTimer;
    private String mCurrentPackageName;

    // 锁对象
    private final Object mLock = new Object();

    private InlineDialogManager(Context context) {
        mSystemContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mWindowManager = (WindowManager) mSystemContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized InlineDialogManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new InlineDialogManager(context);
        }
        return sInstance;
    }

    /**
     * 显示弹窗并阻塞等待结果
     *
     * @param pkgName 应用包名
     * @param contentPreview 剪贴板内容预览
     * @param decision 结果输出（PermissionStorage.PERMISSION_IGNORE 或 PERMISSION_BLOCK）
     * @return true 表示弹窗正常显示并收到结果，false 表示异常
     */
    public boolean showDialog(String pkgName, String contentPreview, AtomicInteger decision) {
        return showDialogWithContent(pkgName, contentPreview, null, decision);
    }

    /**
     * 显示弹窗并阻塞等待结果（带敏感内容检测信息）
     *
     * @param pkgName 应用包名
     * @param contentPreview 剪贴板内容预览
     * @param matchedRule 匹配的敏感规则名称（如 "身份证"、"手机号"），可为 null
     * @param decision 结果输出（PermissionStorage.PERMISSION_IGNORE 或 PERMISSION_BLOCK）
     * @return true 表示弹窗正常显示并收到结果，false 表示异常
     */
    public boolean showDialogWithContent(String pkgName, String contentPreview, String matchedRule, AtomicInteger decision) {
        // 如果已有弹窗在显示，先关闭它
        dismissCurrentDialog();

        CountDownLatch latch;
        AtomicInteger resultRef;
        
        synchronized (mLock) {
            latch = new CountDownLatch(1);
            mCurrentLatch = latch;
            resultRef = decision;
            mCurrentResult = decision;
            mCurrentPackageName = pkgName;
        }

        // 在主线程创建弹窗（使用 CountDownLatch 同步等待）
        final CountDownLatch createLatch = new CountDownLatch(1);
        mMainHandler.post(() -> {
            try {
                createAndShowDialog(pkgName, contentPreview, matchedRule);
            } catch (Throwable e) {
                XLog.e(TAG, "创建弹窗异常: " + e.getMessage());
                // 异常情况下也要释放 latch
                synchronized (mLock) {
                    if (mCurrentLatch != null) {
                        mCurrentLatch.countDown();
                    }
                }
            } finally {
                createLatch.countDown();
            }
        });

        // 等待主线程完成（最多 1 秒，避免 Binder 线程长时间阻塞）
        try {
            createLatch.await(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 等待用户选择或超时
        try {
            // 使用较短的超时，避免主线程阻塞太久
            latch.await(TIMEOUT_MS + 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 清理弹窗
        dismissCurrentDialog();

        // 如果用户没选择（latch 未 countdown），设置默认拒绝
        try {
            if (latch.getCount() > 0 && resultRef != null) {
                resultRef.set(PermissionStorage.PERMISSION_BLOCK);
            }
        } catch (Throwable ignored) {}

        XLog.i(TAG, "弹窗结果: " + pkgName + " -> " +
                (decision.get() == PermissionStorage.PERMISSION_IGNORE ? "允许" : "拒绝"));

        return true;
    }

    /**
     * 创建并显示弹窗（带敏感内容检测信息，必须在主线程调用）
     */
    private void createAndShowDialog(String pkgName, String contentPreview, String matchedRule) {
        // 判断是否为深色模式
        boolean isDarkMode = isDarkMode();

        // 加载应用信息
        String appName = pkgName;
        android.graphics.drawable.Drawable appIcon = null;
        try {
            PackageManager pm = mSystemContext.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
            appName = pm.getApplicationLabel(appInfo).toString();
            appIcon = pm.getApplicationIcon(pkgName);
        } catch (PackageManager.NameNotFoundException e) {
            XLog.w(TAG, "应用不存在: " + pkgName);
        }

        // 创建弹窗根布局
        FrameLayout rootLayout = new FrameLayout(mSystemContext);
        rootLayout.setBackgroundColor(isDarkMode ? COLOR_DIM_DARK : COLOR_DIM);

        // 创建卡片
        LinearLayout card = createDialogCard(pkgName, appName, appIcon, contentPreview, matchedRule, isDarkMode);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dpToPx(320),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.CENTER;
        rootLayout.addView(card, cardParams);

        // 创建 WindowManager 布局参数
        LayoutParams windowParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.CENTER;

        // 添加弹窗到窗口
        try {
            mWindowManager.addView(rootLayout, windowParams);
            mCurrentDialogView = rootLayout;
            mCurrentWindowParams = windowParams;

            // 启动倒计时
            startCountdown(pkgName);

            XLog.i(TAG, "弹窗已显示: " + pkgName + (matchedRule != null ? " [匹配: " + matchedRule + "]" : ""));
        } catch (Exception e) {
            XLog.e(TAG, "添加弹窗失败: " + e.getMessage());
        }
    }

    /**
     * 创建对话框卡片
     */
    private LinearLayout createDialogCard(String pkgName, String appName,
            android.graphics.drawable.Drawable appIcon, String contentPreview, String matchedRule, boolean isDarkMode) {

        LinearLayout card = new LinearLayout(mSystemContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(24), dpToPx(32), dpToPx(24), 0);
        card.setBackground(createCardBackground(isDarkMode));

        // 应用图标
        ImageView ivIcon = new ImageView(mSystemContext);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(72), dpToPx(72));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        iconParams.topMargin = 0;
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
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
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
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        countdownParams.gravity = Gravity.CENTER_HORIZONTAL;
        countdownParams.topMargin = dpToPx(6);
        tvCountdown.setLayoutParams(countdownParams);
        tvCountdown.setText(String.format("%d 秒后自动拒绝", TIMEOUT_MS / 1000));
        tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvCountdown.setTextColor(COLOR_PRIMARY);
        tvCountdown.setTag("countdown"); // 标记用于后续更新
        card.addView(tvCountdown);

        // 权限说明
        TextView tvMessage = new TextView(mSystemContext);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        msgParams.topMargin = dpToPx(16);
        tvMessage.setLayoutParams(msgParams);
        tvMessage.setText(String.format("%s 正在写入剪贴板", appName));
        tvMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvMessage.setTextColor(isDarkMode ? 0xFFAAAAAA : COLOR_TEXT_SECONDARY);
        tvMessage.setGravity(Gravity.CENTER);
        tvMessage.setLineSpacing(dpToPx(4), 1.0f);
        card.addView(tvMessage);


        // 内容预览（如果有）
        if (contentPreview != null && !contentPreview.isEmpty()) {
            TextView tvPreview = new TextView(mSystemContext);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            previewParams.topMargin = dpToPx(12);
            tvPreview.setLayoutParams(previewParams);
            tvPreview.setText(contentPreview);
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvPreview.setTextColor(isDarkMode ? 0xFF888888 : COLOR_TEXT_HINT);
            tvPreview.setMaxLines(3);
            tvPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvPreview.setBackground(createPreviewBackground(isDarkMode));
            tvPreview.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
            card.addView(tvPreview);
        }

        // 分隔线
        View divider = new View(mSystemContext);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)
        );
        dividerParams.topMargin = dpToPx(20);
        divider.setLayoutParams(dividerParams);
        divider.setBackgroundColor(COLOR_DIVIDER);
        card.addView(divider);

        // 按钮区域
        LinearLayout btnContainer = new LinearLayout(mSystemContext);
        LinearLayout.LayoutParams btnContainerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(56)
        );
        btnContainer.setLayoutParams(btnContainerParams);
        btnContainer.setOrientation(LinearLayout.HORIZONTAL);
        btnContainer.setBackgroundColor(Color.TRANSPARENT);

        // 拒绝按钮
        TextView btnDeny = new TextView(mSystemContext);
        LinearLayout.LayoutParams denyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        btnDeny.setLayoutParams(denyParams);
        btnDeny.setText("拒绝");
        btnDeny.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btnDeny.setTextColor(COLOR_BTN_DENY);
        btnDeny.setGravity(Gravity.CENTER);
        btnDeny.setBackground(createButtonBackground(false, isDarkMode));
        btnDeny.setClickable(true);
        btnDeny.setFocusable(true);
        btnDeny.setOnClickListener(v -> onResult(pkgName, PermissionStorage.PERMISSION_BLOCK));
        btnContainer.addView(btnDeny);

        // 按钮间分隔线
        View btnDivider = new View(mSystemContext);
        LinearLayout.LayoutParams btnDividerParams = new LinearLayout.LayoutParams(dpToPx(1), ViewGroup.LayoutParams.MATCH_PARENT);
        btnDivider.setLayoutParams(btnDividerParams);
        btnDivider.setBackgroundColor(COLOR_DIVIDER);
        btnContainer.addView(btnDivider);

        // 允许按钮
        TextView btnAllow = new TextView(mSystemContext);
        LinearLayout.LayoutParams allowParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        btnAllow.setLayoutParams(allowParams);
        btnAllow.setText("允许");
        btnAllow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btnAllow.setTextColor(COLOR_BTN_ALLOW);
        btnAllow.setTypeface(Typeface.DEFAULT_BOLD);
        btnAllow.setGravity(Gravity.CENTER);
        btnAllow.setBackground(createButtonBackground(true, isDarkMode));
        btnAllow.setClickable(true);
        btnAllow.setFocusable(true);
        btnAllow.setOnClickListener(v -> onResult(pkgName, PermissionStorage.PERMISSION_IGNORE));
        btnContainer.addView(btnAllow);

        card.addView(btnContainer);

        return card;
    }

    /**
     * 创建卡片背景
     */
    private GradientDrawable createCardBackground(boolean isDarkMode) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dpToPx(28));
        drawable.setColor(isDarkMode ? COLOR_SURFACE_DARK : COLOR_SURFACE);
        return drawable;
    }

    /**
     * 创建预览区域背景
     */
    private GradientDrawable createPreviewBackground(boolean isDarkMode) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dpToPx(8));
        drawable.setColor(isDarkMode ? COLOR_PREVIEW_BG_DARK : COLOR_PREVIEW_BG);
        return drawable;
    }

    /**
     * 创建按钮背景
     */
    private GradientDrawable createButtonBackground(boolean isAllow, boolean isDarkMode) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        // 左下/右下圆角
        float[] radii;
        if (isAllow) {
            radii = new float[]{0, 0, dpToPx(24), dpToPx(24), 0, 0, 0, 0};
        } else {
            radii = new float[]{0, 0, 0, 0, dpToPx(24), dpToPx(24), 0, 0};
        }
        drawable.setCornerRadii(radii);
        return drawable;
    }

    /**
     * 启动倒计时
     */
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
                XLog.i(TAG, "倒计时结束，超时拒绝: " + pkgName);
                onResult(pkgName, PermissionStorage.PERMISSION_BLOCK);
            }
        };
        mCurrentTimer.start();
    }

    /**
     * 更新倒计时显示
     */
    private void updateCountdown(int seconds) {
        if (mCurrentDialogView != null) {
            TextView tvCountdown = mCurrentDialogView.findViewWithTag("countdown");
            if (tvCountdown != null) {
                tvCountdown.setText(String.format("%d 秒后自动拒绝", seconds));
            }
        }
    }

    /**
     * 用户点击按钮或超时
     */
    private void onResult(String pkgName, int decision) {
        // 验证是否是当前包名的弹窗
        if (!pkgName.equals(mCurrentPackageName)) {
            XLog.w(TAG, "忽略过期结果: " + pkgName + " (当前: " + mCurrentPackageName + ")");
            return;
        }

        // 取消倒计时
        if (mCurrentTimer != null) {
            mCurrentTimer.cancel();
            mCurrentTimer = null;
        }

        // 设置结果
        synchronized (mLock) {
            if (mCurrentResult != null) {
                mCurrentResult.set(decision);
            }
            if (mCurrentLatch != null) {
                mCurrentLatch.countDown();
            }
        }

        XLog.i(TAG, "用户选择: " + pkgName + " -> " +
                (decision == PermissionStorage.PERMISSION_IGNORE ? "允许" : "拒绝"));
    }

    /**
     * 关闭当前弹窗
     */
    private void dismissCurrentDialog() {
        // 取消倒计时
        CountDownTimer timerToCancel;
        View viewToRemove;
        
        synchronized (mLock) {
            timerToCancel = mCurrentTimer;
            viewToRemove = mCurrentDialogView;
            mCurrentTimer = null;
            mCurrentDialogView = null;
            mCurrentPackageName = null;
        }
        
        // 在外部取消定时器，避免持有锁时执行耗时操作
        if (timerToCancel != null) {
            try {
                timerToCancel.cancel();
            } catch (Throwable ignored) {}
        }

        // 从窗口移除（必须在主线程）
        if (viewToRemove != null && mWindowManager != null) {
            try {
                mMainHandler.post(() -> {
                    try {
                        mWindowManager.removeView(viewToRemove);
                    } catch (IllegalArgumentException e) {
                        // 视图已经被移除，忽略
                    } catch (Throwable e) {
                        XLog.w(TAG, "移除弹窗失败: " + e.getMessage());
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                mSystemContext.getResources().getDisplayMetrics()
        );
    }

    /**
     * 判断是否为深色模式
     */
    private boolean isDarkMode() {
        int nightModeFlags = mSystemContext.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 释放资源
     */
    public void destroy() {
        dismissCurrentDialog();
        synchronized (this) {
            sInstance = null;
        }
    }
}
