package com.android.clipboardguard;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedDispatcher;
import androidx.activity.OnBackPressedCallback;

/**
 * 权限询问弹窗 - 仿iOS风格对话框
 *
 * 设计原则：
 * - 只做本次询问（允许/拒绝），不保存永久状态
 * - 永久管理交给主界面的应用列表（勾选放行）
 * - 超时5秒自动拒绝，返回空值保护用户隐私
 */
public class PermissionDialogActivity extends AppCompatActivity {

    private static final String TAG = "ClipboardGuard.Dialog";
    public static final String EXTRA_PACKAGE_NAME    = "package_name";
    public static final String EXTRA_CONTENT_PREVIEW = "content_preview";
    private static final long TIMEOUT_MS       = 4_000;
    private static final long TIMER_INTERVAL_MS = 1_000;

    // 主题设置（与 MainActivity 一致）
    private static final String PREF_NAME = "settings";
    private static final String KEY_THEME = "theme";
    private static final int THEME_LIGHT = 0;
    private static final int THEME_DARK   = 1;
    private static final int THEME_SYSTEM = 2;

    private String mPackageName;
    private TextView mTvCountdown;
    private CountDownTimer mCountDownTimer;
    private boolean mResultSent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 先应用主题（与主程序一致）
        applyTheme();

        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_permission_dialog);

        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.dimAmount = 0.5f;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        setFinishOnTouchOutside(false);

        // 注册 BackPressed 回调（处理系统返回键 + 手势）
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                sendResult(PermissionStorage.PERMISSION_BLOCK);
            }
        });

        mPackageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        String contentPreview = getIntent().getStringExtra(EXTRA_CONTENT_PREVIEW);

        XLog.i(TAG, "弹窗创建: " + mPackageName + ", 内容: " + contentPreview);

        if (mPackageName == null) {
            XLog.w(TAG, "packageName为空，关闭");
            finish();
            return;
        }
        initViews(contentPreview);
        startCountdown();
    }

    private void initViews(String contentPreview) {
        ImageView ivAppIcon         = findViewById(R.id.iv_app_icon);
        TextView  tvAppName         = findViewById(R.id.tv_app_name);
        TextView  tvMessage         = findViewById(R.id.tv_message);
        TextView  tvContentPreview  = findViewById(R.id.tv_content_preview);
        mTvCountdown                = findViewById(R.id.tv_countdown);
        View btnAllow               = findViewById(R.id.btn_allow);
        View btnDeny                = findViewById(R.id.btn_deny);

        String appName = mPackageName;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(mPackageName, 0);
            appName = pm.getApplicationLabel(appInfo).toString();
            Drawable icon = pm.getApplicationIcon(appInfo);
            ivAppIcon.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            ivAppIcon.setImageResource(R.drawable.ic_app_default);
            XLog.w(TAG, "应用不存在: " + mPackageName);
        }

        tvAppName.setText(appName);
        tvMessage.setText(getString(R.string.permission_message, appName));

        if (contentPreview != null && !contentPreview.isEmpty()) {
            tvContentPreview.setVisibility(View.VISIBLE);
            tvContentPreview.setText(getString(R.string.content_preview, contentPreview));
        } else {
            tvContentPreview.setVisibility(View.GONE);
        }

        // 允许 = 本次放行，写1(IGNORE)
        btnAllow.setOnClickListener(v -> sendResult(PermissionStorage.PERMISSION_IGNORE));
        // 拒绝 = 本次拦截，写0(BLOCK)
        btnDeny.setOnClickListener(v -> sendResult(PermissionStorage.PERMISSION_BLOCK));
    }

    private void startCountdown() {
        mCountDownTimer = new CountDownTimer(TIMEOUT_MS, TIMER_INTERVAL_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) (millisUntilFinished / 1000);
                if (mTvCountdown != null) {
                    mTvCountdown.setText(getString(R.string.countdown_format, seconds));
                }
            }

            @Override
            public void onFinish() {
                XLog.i(TAG, "超时，自动拒绝: " + mPackageName);
                sendResult(PermissionStorage.PERMISSION_BLOCK);
            }
        };
        mCountDownTimer.start();
    }

    private void sendResult(int decision) {
        if (mResultSent) return;
        mResultSent = true;

        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }

        XLog.i(TAG, "发送结果: " + mPackageName + " -> " +
                (decision == PermissionStorage.PERMISSION_IGNORE ? "允许" : "拒绝"));

        // 写入 pending 表（不保存永久状态，由主界面管理）
        PermissionProvider.writePendingResult(this, mPackageName, decision, false);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }
    }

    /** 应用与主程序一致的主题设置 */
    private void applyTheme() {
        int theme = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM);
        switch (theme) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
