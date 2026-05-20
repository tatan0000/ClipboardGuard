package com.android.clipboardguard;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * 关于模块页。
 * 负责展示模块简介、版本信息、参考项目与致谢内容，避免把设置页详情继续堆在 MainActivity 中。
 */
public class AboutModuleActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_module);

        applyStatusBarAdaptation();
        applyAppBarInsets();
        initToolbar();
        bindAboutInfo();
    }

    /**
     * 给顶部应用栏补上状态栏高度，避免真机上标题与系统状态栏重叠。
     * 处理方式与主页、规则页保持一致。
     */
    private void applyAppBarInsets() {
        android.view.View appBarView = findViewById(R.id.app_bar);
        if (appBarView == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0),
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarView);
    }

    /** 初始化顶部工具栏，返回按钮只关闭当前关于页。 */
    private void initToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(R.string.settings_about_module);
            toolbar.setNavigationIcon(R.drawable.ic_back);
            toolbar.setNavigationOnClickListener(v -> finish());
        }
    }

    /** 绑定版本号与致谢链接，链接点击交给系统浏览器处理。 */
    private void bindAboutInfo() {
        TextView tvVersion = findViewById(R.id.tv_about_version);
        TextView tvNotes = findViewById(R.id.tv_about_notes);
        TextView tvAck = findViewById(R.id.tv_about_ack);

        if (tvVersion != null) {
            tvVersion.setText(getString(R.string.about_version_format, getModuleVersion()));
        }
        if (tvNotes != null) {
            tvNotes.setText(Html.fromHtml(getString(R.string.about_notes_content), Html.FROM_HTML_MODE_LEGACY));
            tvNotes.setMovementMethod(ExactLinkMovementMethod.getInstance());
        }
        if (tvAck != null) {
            tvAck.setText(Html.fromHtml(getString(R.string.about_ack_content), Html.FROM_HTML_MODE_LEGACY));
            tvAck.setMovementMethod(ExactLinkMovementMethod.getInstance());
        }
    }

    /** 获取当前安装包版本，异常时显示占位符，避免关于页启动失败。 */
    private String getModuleVersion() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName + " (" + packageInfo.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "--";
        }
    }

    /**
     * 适配关于页系统栏颜色。
     * 项目 minSdk 为 30，可直接使用 WindowInsetsController，避免保留旧版已弃用 API 分支。
     */
    private void applyStatusBarAdaptation() {
        int theme = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getInt(MainActivity.KEY_THEME, MainActivity.THEME_SYSTEM);
        boolean isDark = (theme == MainActivity.THEME_DARK)
                || (theme == MainActivity.THEME_SYSTEM && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES);

        Window window = getWindow();
        window.setStatusBarColor(isDark ? 0xFF1C1B1F : 0xFFFFFFFF);
        window.setNavigationBarColor(0xFF000000);

        // 浅色主题使用深色状态栏图标，暗色主题交给系统默认浅色图标。
        WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    isDark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }

        // 允许内容延伸到刘海区域边缘，和项目其它页面保持一致。
        window.getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    }

    /** 仅点到链接文字本身时才跳转，避免空白处误触。 */
    static final class ExactLinkMovementMethod extends LinkMovementMethod {
        private static final ExactLinkMovementMethod INSTANCE = new ExactLinkMovementMethod();

        public static ExactLinkMovementMethod getInstance() {
            return INSTANCE;
        }

        @Override
        public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            int action = event.getAction();
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) {
                return super.onTouchEvent(widget, buffer, event);
            }

            int x = (int) event.getX();
            int y = (int) event.getY();
            x -= widget.getTotalPaddingLeft();
            y -= widget.getTotalPaddingTop();
            x += widget.getScrollX();
            y += widget.getScrollY();

            Layout layout = widget.getLayout();
            if (layout == null) return false;

            int line = layout.getLineForVertical(y);
            int offset = layout.getOffsetForHorizontal(line, x);
            ClickableSpan[] spans = buffer.getSpans(offset, offset, ClickableSpan.class);
            if (spans.length == 0) return false;

            ClickableSpan span = spans[0];
            int start = buffer.getSpanStart(span);
            int end = buffer.getSpanEnd(span);
            if (start < 0 || end <= start) return false;

            float left = layout.getPrimaryHorizontal(start);
            float right = layout.getPrimaryHorizontal(end);
            float min = Math.min(left, right);
            float max = Math.max(left, right);
            if (x < min || x > max) return false;

            if (action == MotionEvent.ACTION_UP) {
                span.onClick(widget);
            }
            return true;
        }
    }
}
