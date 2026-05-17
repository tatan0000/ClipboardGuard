package com.android.clipboardguard;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WriteRulesDetailActivity extends AppCompatActivity {

    private RecyclerView mRvWriteRulesDetail;
    private SwitchCompat mSwitchWriteRulesEnabled;

    private boolean mWriteRulesEnabled = false;
    private final List<ContentRule> mWriteRules = new ArrayList<>();
    private WriteRulesAdapter mWriteRulesAdapter;

    private boolean mWriteRulesSelectionMode = false;
    private final Set<ContentRule> mWriteSelectedRules = new HashSet<>();

    private View mWriteBatchCard;
    private TextView mWriteSelectedCount;

    private boolean mShowDefaultRules = false;
    private final List<ContentRule> mWriteDefaultRules = new ArrayList<>();
    private WriteRulesAdapter mWriteDefaultRulesAdapter;
    private View mWriteDefaultBatchCard;
    private TextView mWriteDefaultSelectedCount;
    private final Set<ContentRule> mWriteDefaultSelectedRules = new HashSet<>();
    private boolean mWriteDefaultSelectionMode = false;
    private View mContainerMainRules;
    private View mContainerDefaultRules;
    private Toolbar mToolbar;

    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService mExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean mDestroyed = false;

    private AlertDialog mCurrentRuleDialog;

    // ═══════════════════════════════════════════════════════════════
    // 生命周期与基础初始化
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_write_rules_detail);

        View appBarView = findViewById(R.id.app_bar);
        ViewCompat.setOnApplyWindowInsetsListener(appBarView, (v, insets) -> {
            int statusH = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), Math.max(statusH - 8, 0),
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(appBarView);

        applyStatusBarAdaptation();
        initToolbar();
        initViews();
        initWriteRulesDetailPage();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mWriteRulesSelectionMode) exitWriteSelectionMode();
                else if (mWriteDefaultSelectionMode) exitWriteDefaultSelectionMode();
                else if (mShowDefaultRules) showMainRulesPage();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
    }

    private void initToolbar() {
        mToolbar = findViewById(R.id.toolbar);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.write_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
            mToolbar.setNavigationOnClickListener(v -> {
                if (mShowDefaultRules) showMainRulesPage();
                else finish();
            });
        } else if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.write_rules_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void applyStatusBarAdaptation() {
        int theme = getSharedPreferences("clipboardguard_prefs", MODE_PRIVATE)
                .getInt("theme", MainActivity.THEME_SYSTEM);
        boolean isDark = (theme == MainActivity.THEME_DARK)
                || (theme == MainActivity.THEME_SYSTEM && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        Window w = getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(isDark ? Color.BLACK : Color.WHITE);
        WindowInsetsController controller = w.getDecorView().getWindowInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    isDark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    private void initViews() {
        mContainerMainRules = findViewById(R.id.container_main_rules);
        mContainerDefaultRules = findViewById(R.id.container_default_rules);

        mRvWriteRulesDetail = findViewById(R.id.rv_write_rules_detail);
        mSwitchWriteRulesEnabled = findViewById(R.id.switch_write_rules_enabled);
        TextView tvWriteRulesHint = findViewById(R.id.tv_write_rules_hint);
        MaterialButton btnAddWriteRule = findViewById(R.id.btn_add_write_rule);

        mWriteBatchCard = findViewById(R.id.card_batch_actions_main);
        mWriteSelectedCount = findViewById(R.id.tv_selected_count);
        TextView btnSelectAll = findViewById(R.id.btn_select_all_rules);
        TextView writeDeleteSelected = findViewById(R.id.btn_delete_selected);
        TextView writeEnableSelected = findViewById(R.id.btn_enable_selected);
        TextView writeDisableSelected = findViewById(R.id.btn_disable_selected);

        if (tvWriteRulesHint != null) {
            tvWriteRulesHint.setText(R.string.rules_write_hint);
        }

        mSwitchWriteRulesEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mWriteRulesEnabled = isChecked;
            saveWriteRules(); // 用户操作，保存并广播
        });

        btnAddWriteRule.setOnClickListener(v -> showEditWriteRuleDialog());

        btnSelectAll.setOnClickListener(v -> {
            if (mWriteSelectedRules.size() == mWriteRules.size()) mWriteSelectedRules.clear();
            else { mWriteSelectedRules.clear(); mWriteSelectedRules.addAll(mWriteRules); }
            if (mWriteRulesAdapter != null) mWriteRulesAdapter.refreshSelectionMode();
            updateWriteSelectedCount();
        });

        writeDeleteSelected.setOnClickListener(v -> deleteWriteSelectedRules());
        writeEnableSelected.setOnClickListener(v -> enableWriteSelectedRules(true));
        writeDisableSelected.setOnClickListener(v -> enableWriteSelectedRules(false));

        View cardDefaultRules = findViewById(R.id.card_write_default_rules);
        if (cardDefaultRules != null) cardDefaultRules.setOnClickListener(v -> showDefaultRulesPage());

        RecyclerView rvDefaultRules = findViewById(R.id.rv_write_default_rules);
        mWriteDefaultBatchCard = findViewById(R.id.card_batch_actions_write_default);
        mWriteDefaultSelectedCount = findViewById(R.id.tv_default_selected_count);
        TextView btnEnableDefault = findViewById(R.id.btn_enable_selected_default);
        TextView btnDisableDefault = findViewById(R.id.btn_disable_selected_default);
        TextView btnSelectAllDefault = findViewById(R.id.btn_select_all_default);

        btnEnableDefault.setOnClickListener(v -> enableWriteDefaultSelected(true));
        btnDisableDefault.setOnClickListener(v -> enableWriteDefaultSelected(false));
        btnSelectAllDefault.setOnClickListener(v -> toggleWriteDefaultSelectAll());

        if (mWriteDefaultRulesAdapter == null) {
            mWriteDefaultRulesAdapter = new WriteRulesAdapter(mWriteDefaultRules, true);
        }
        rvDefaultRules.setLayoutManager(new LinearLayoutManager(this));
        rvDefaultRules.setAdapter(mWriteDefaultRulesAdapter);

        mHandler.post(this::loadDefaultWriteRulesAsync);
    }

    // ═══════════════════════════════════════════════════════════════
    // 页面初始化与切换
    // ═══════════════════════════════════════════════════════════════

    private void initWriteRulesDetailPage() {
        if (mWriteRulesAdapter == null) mWriteRulesAdapter = new WriteRulesAdapter(mWriteRules);
        mRvWriteRulesDetail.setLayoutManager(new LinearLayoutManager(this));
        mRvWriteRulesDetail.setAdapter(mWriteRulesAdapter);
        mHandler.post(this::loadWriteRulesSync);
    }

    private void showDefaultRulesPage() {
        mShowDefaultRules = true;
        if (mContainerMainRules != null) mContainerMainRules.setVisibility(View.GONE);
        if (mContainerDefaultRules != null) mContainerDefaultRules.setVisibility(View.VISIBLE);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.default_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
        }
        loadDefaultWriteRulesAsync();
    }

    private void showMainRulesPage() {
        mShowDefaultRules = false;
        exitWriteDefaultSelectionMode();
        if (mContainerMainRules != null) mContainerMainRules.setVisibility(View.VISIBLE);
        if (mContainerDefaultRules != null) mContainerDefaultRules.setVisibility(View.GONE);
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.write_rules_title);
            mToolbar.setNavigationIcon(R.drawable.ic_back);
        }
    }

    // 加载默认规则（只读文件，不自动保存/广播）
    private void loadDefaultWriteRulesAsync() {
        File file = new File(getFilesDir(), "write_default_rules.json");
        String[][] template = getWriteDefaultRulesTemplate();
        Map<String, Boolean> oldEnabledStates = new HashMap<>();

        if (file.exists()) {
            try {
                String content = readFile(file);
                JSONArray arr = new JSONArray(content);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    oldEnabledStates.put(obj.getString("name"), obj.getBoolean("enabled"));
                }
            } catch (Exception e) {
                XLog.e("ClipboardGuard-Rules", "loadDefaultWriteRules failed", e);
            }
        }

        mWriteDefaultRules.clear();
        for (String[] ruleDef : template) {
            Boolean oldEnabled = oldEnabledStates.get(ruleDef[0]);
            boolean enabled = oldEnabled != null && oldEnabled;
            mWriteDefaultRules.add(new ContentRule(ruleDef[0], ruleDef[1], enabled, true));
        }

        // 如果文件不存在（首次），创建默认文件；否则不触发保存和广播
        if (!file.exists()) {
            saveDefaultWriteRulesToFile(); // 仅写入文件，不广播
        }

        mHandler.post(this::refreshWriteDefaultRulesAdapter);
    }

    private String[][] getWriteDefaultRulesTemplate() {
        return new String[][] {
                {"广告关键词", "(?:推广|广告|秒杀|限时抢购|领券|优惠券)"},
                {"电商口令", "[￥$₴¢€£¥√|*#][A-Za-z0-9]{3,}[￥$₴¢€£¥√|*#]"}
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // 默认写入规则选择
    // ═══════════════════════════════════════════════════════════════

    private void enterWriteDefaultSelectionMode(ContentRule rule) {
        mWriteDefaultSelectionMode = true;
        mWriteDefaultSelectedRules.clear();
        mWriteDefaultSelectedRules.add(rule);
        if (mWriteDefaultRulesAdapter != null) mWriteDefaultRulesAdapter.refreshSelectionMode();
        if (mWriteDefaultBatchCard != null) mWriteDefaultBatchCard.setVisibility(View.VISIBLE);
        updateWriteDefaultSelectedCount();
    }

    private void exitWriteDefaultSelectionMode() {
        mWriteDefaultSelectionMode = false;
        mWriteDefaultSelectedRules.clear();
        if (mWriteDefaultRulesAdapter != null) mWriteDefaultRulesAdapter.refreshSelectionMode();
        if (mWriteDefaultBatchCard != null) mWriteDefaultBatchCard.setVisibility(View.GONE);
    }

    private void updateWriteDefaultSelectedCount() {
        if (mWriteDefaultSelectedCount != null)
            mWriteDefaultSelectedCount.setText(getString(R.string.selected_count, mWriteDefaultSelectedRules.size()));
    }

    private void enableWriteDefaultSelected(boolean enable) {
        if (mWriteDefaultSelectedRules.isEmpty()) return;
        for (ContentRule rule : mWriteDefaultSelectedRules) rule.enabled = enable;
        saveDefaultWriteRules(); // 用户操作，保存并广播
        refreshWriteDefaultRulesAdapter();
        exitWriteDefaultSelectionMode();
    }

    private void toggleWriteDefaultSelectAll() {
        if (mWriteDefaultSelectedRules.size() == mWriteDefaultRules.size()) mWriteDefaultSelectedRules.clear();
        else { mWriteDefaultSelectedRules.clear(); mWriteDefaultSelectedRules.addAll(mWriteDefaultRules); }
        if (mWriteDefaultRulesAdapter != null) mWriteDefaultRulesAdapter.refreshSelectionMode();
        updateWriteDefaultSelectedCount();
    }

    // ═══════════════════════════════════════════════════════════════
    // 保存方法（仅在用户操作时调用）
    // ═══════════════════════════════════════════════════════════════

    private void saveWriteRules() {
        try {
            JSONObject root = new JSONObject();
            root.put("enabled", mWriteRulesEnabled);
            JSONArray arr = new JSONArray();
            for (ContentRule rule : mWriteRules) arr.put(rule.toJson());
            root.put("content_rules", arr);
            File file = new File(getFilesDir(), "write_rules.json");
            writeFile(file, root.toString(2));

            sendMergedWriteRulesBroadcast();
            XLog.i("ClipboardGuard-Rules", "已保存写入规则，自定义规则数=" + arr.length());
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "saveWriteRules failed", e);
        }
    }

    /** 用户操作默认规则后保存并广播 */
    private void saveDefaultWriteRules() {
        saveDefaultWriteRulesToFile();
        sendMergedWriteRulesBroadcast();
        XLog.i("ClipboardGuard-Rules", "已保存并广播默认写入规则更新");
    }

    /** 仅写入文件，不广播（用于首次初始化） */
    private void saveDefaultWriteRulesToFile() {
        try {
            JSONArray arr = new JSONArray();
            for (ContentRule rule : mWriteDefaultRules) arr.put(rule.toJson());
            File file = new File(getFilesDir(), "write_default_rules.json");
            writeFile(file, arr.toString());
            XLog.i("ClipboardGuard-Rules", "已写入默认写入规则文件");
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "saveDefaultWriteRulesToFile failed", e);
        }
    }

    /**
     * 发送合并后的写入规则广播（自定义规则 + 启用的默认规则）
     */
    private void sendMergedWriteRulesBroadcast() {
        try {
            JSONObject mergedRoot = new JSONObject();
            mergedRoot.put("enabled", mWriteRulesEnabled);
            JSONArray mergedArr = new JSONArray();

            for (ContentRule rule : mWriteRules) mergedArr.put(rule.toJson());
            for (ContentRule rule : mWriteDefaultRules) {
                if (rule.enabled) mergedArr.put(rule.toJson());
            }

            mergedRoot.put("content_rules", mergedArr);

            Intent intent = new Intent(PermissionProvider.ACTION_PERMISSION_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra("write_rules_json", mergedRoot.toString());
            sendBroadcast(intent);

            XLog.i("ClipboardGuard-Rules", "已发送合并写入规则广播，总规则数=" + mergedArr.length());
        } catch (Exception e) {
            XLog.e("ClipboardGuard-Rules", "sendMergedWriteRulesBroadcast failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 对话框
    // ═══════════════════════════════════════════════════════════════

    private void showViewRuleDialog(ContentRule rule) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_view_rule, null);
        TextView tvName = dialogView.findViewById(R.id.tv_rule_name);
        TextView tvPattern = dialogView.findViewById(R.id.tv_rule_pattern);
        TextView tvStatus = dialogView.findViewById(R.id.tv_rule_status);
        tvName.setText(rule.name);
        tvPattern.setText(rule.pattern);
        tvStatus.setText(rule.enabled ? "已启用" : "已禁用");
        tvStatus.setTextColor(rule.enabled ?
                ContextCompat.getColor(this, R.color.status_active) :
                ContextCompat.getColor(this, R.color.status_inactive));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rules_dialog_title_view)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showEditWriteRuleDialog() { showEditRuleDialog(null, false); }

    private void showEditRuleDialog(ContentRule rule, boolean isEdit) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_rule, null);
        TextInputLayout tilName = dialogView.findViewById(R.id.til_rule_name);
        TextInputLayout tilPattern = dialogView.findViewById(R.id.til_rule_pattern);
        TextInputEditText etName = dialogView.findViewById(R.id.et_rule_name);
        TextInputEditText etPattern = dialogView.findViewById(R.id.et_rule_pattern);

        if (isEdit && rule != null) { etName.setText(rule.name); etPattern.setText(rule.pattern); }

        String title = isEdit ? getString(R.string.rules_dialog_title_edit) : getString(R.string.rules_dialog_title_add);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(title).setView(dialogView)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(android.R.string.cancel, null);

        mCurrentRuleDialog = builder.create();
        mCurrentRuleDialog.setCanceledOnTouchOutside(false);
        mCurrentRuleDialog.setOnShowListener(dialog -> {
            Button positiveButton = mCurrentRuleDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) positiveButton.setOnClickListener(v ->
                    attemptSaveRule(etName, etPattern, tilName, tilPattern, rule, isEdit));
            Button negativeButton = mCurrentRuleDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negativeButton != null) negativeButton.setOnClickListener(v -> mCurrentRuleDialog.dismiss());
        });
        mCurrentRuleDialog.show();

        TextWatcher textWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateSaveButtonState(etName, etPattern, tilName, tilPattern); }
        };
        etName.addTextChangedListener(textWatcher);
        etPattern.addTextChangedListener(textWatcher);
        updateSaveButtonState(etName, etPattern, tilName, tilPattern);
    }

    private void updateSaveButtonState(TextInputEditText etName, TextInputEditText etPattern,
                                       TextInputLayout tilName, TextInputLayout tilPattern) {
        if (mCurrentRuleDialog == null) return;
        Button saveButton = mCurrentRuleDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (saveButton == null) return;
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String pattern = etPattern.getText() != null ? etPattern.getText().toString().trim() : "";
        saveButton.setEnabled(!name.isEmpty() && !pattern.isEmpty());
        tilName.setError(null); tilPattern.setError(null);
    }

    private void attemptSaveRule(TextInputEditText etName, TextInputEditText etPattern,
                                 TextInputLayout tilName, TextInputLayout tilPattern,
                                 ContentRule rule, boolean isEdit) {
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        String pattern = etPattern.getText() != null ? etPattern.getText().toString().trim() : "";
        if (name.isEmpty()) { tilName.setError(getString(R.string.rules_name_required)); return; }
        tilName.setError(null);
        try { java.util.regex.Pattern.compile(pattern); tilPattern.setError(null); }
        catch (Exception e) { tilPattern.setError(getString(R.string.rules_regex_error)); return; }

        if (isEdit && rule != null) {
            rule.name = name; rule.pattern = pattern; rule.compilePattern();
        } else {
            mWriteRules.add(new ContentRule(name, pattern, true));
        }
        saveWriteRules(); // 用户操作，保存并广播
        refreshWriteRulesAdapter();
        mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
        mCurrentRuleDialog.dismiss();
    }

    // ═══════════════════════════════════════════════════════════════
    // 批量选择
    // ═══════════════════════════════════════════════════════════════

    private void enterWriteSelectionMode(ContentRule rule) {
        mWriteRulesSelectionMode = true; mWriteSelectedRules.clear(); mWriteSelectedRules.add(rule);
        if (mWriteRulesAdapter != null) mWriteRulesAdapter.refreshSelectionMode();
        if (mWriteBatchCard != null) mWriteBatchCard.setVisibility(View.VISIBLE);
        updateWriteSelectedCount();
    }
    private void exitWriteSelectionMode() {
        mWriteRulesSelectionMode = false; mWriteSelectedRules.clear();
        if (mWriteRulesAdapter != null) mWriteRulesAdapter.refreshSelectionMode();
        if (mWriteBatchCard != null) mWriteBatchCard.setVisibility(View.GONE);
    }
    private void updateWriteSelectedCount() {
        if (mWriteSelectedCount != null) mWriteSelectedCount.setText(getString(R.string.selected_count, mWriteSelectedRules.size()));
    }
    private void deleteWriteSelectedRules() {
        int count = mWriteSelectedRules.size();
        if (count == 0) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rules_delete_confirm_title)
                .setMessage(getString(R.string.rules_delete_selected_confirm, count))
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    mWriteRules.removeAll(mWriteSelectedRules);
                    saveWriteRules(); // 用户操作，保存并广播
                    refreshWriteRulesAdapter();
                    mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
                    exitWriteSelectionMode();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void deleteWriteRule(ContentRule rule) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.rules_delete_confirm)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    mWriteRules.remove(rule);
                    saveWriteRules(); // 用户操作，保存并广播
                    refreshWriteRulesAdapter();
                    mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void enableWriteSelectedRules(boolean enable) {
        if (mWriteSelectedRules.isEmpty()) return;
        for (ContentRule rule : mWriteSelectedRules) rule.enabled = enable;
        saveWriteRules(); // 用户操作，保存并广播
        refreshWriteRulesAdapter();
        exitWriteSelectionMode();
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据加载
    // ═══════════════════════════════════════════════════════════════

    private void loadWriteRulesSync() {
        mExecutor.execute(() -> {
            boolean enabled = false;
            List<ContentRule> rules = new ArrayList<>();
            try {
                File file = new File(getFilesDir(), "write_rules.json");
                if (file.exists()) {
                    String content = readFile(file);
                    JSONObject root = new JSONObject(content);
                    enabled = root.optBoolean("enabled", false);
                    JSONArray arr = root.optJSONArray("content_rules");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            ContentRule rule = ContentRule.fromJson(arr.getJSONObject(i));
                            if (!rule.isDefault) rules.add(rule);
                        }
                    }
                }
            } catch (Exception e) { XLog.e("ClipboardGuard-Rules", "loadWriteRulesSync failed", e); }

            final boolean fe = enabled;
            final List<ContentRule> fr = new ArrayList<>(rules);
            mHandler.post(() -> {
                if (mDestroyed || isFinishing() || isDestroyed()) return;
                mWriteRulesEnabled = fe;
                mWriteRules.clear();
                mWriteRules.addAll(fr);
                // 设置开关状态，暂移除监听器防止触发保存
                if (mSwitchWriteRulesEnabled != null) {
                    mSwitchWriteRulesEnabled.setOnCheckedChangeListener(null);
                    mSwitchWriteRulesEnabled.setChecked(fe);
                    mSwitchWriteRulesEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        mWriteRulesEnabled = isChecked;
                        saveWriteRules();
                    });
                }
                refreshWriteRulesAdapter();
                if (mRvWriteRulesDetail != null)
                    mRvWriteRulesDetail.setVisibility(mWriteRules.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // 文件工具
    // ═══════════════════════════════════════════════════════════════

    private String readFile(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(fis))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void writeFile(File file, String content) {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8)); fos.flush();
        } catch (Exception e) { XLog.e("ClipboardGuard", "writeFile failed: " + file.getName(), e); }
    }

    // ═══════════════════════════════════════════════════════════════
    // Adapter
    // ═══════════════════════════════════════════════════════════════

    class WriteRulesAdapter extends RecyclerView.Adapter<WriteRulesAdapter.WriteRuleViewHolder> {
        private final List<ContentRule> mRulesList;
        private final boolean mIsDefaultRules;
        WriteRulesAdapter(List<ContentRule> rules) { this(rules, false); }
        WriteRulesAdapter(List<ContentRule> rules, boolean isDefaultRules) { mRulesList = rules; mIsDefaultRules = isDefaultRules; }

        @Override
        @androidx.annotation.NonNull
        public WriteRuleViewHolder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            return new WriteRuleViewHolder(getLayoutInflater().inflate(R.layout.item_content_rule, parent, false));
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull WriteRuleViewHolder holder, int position) {
            ContentRule rule = mRulesList.get(position);
            boolean selectionMode = mIsDefaultRules ? mWriteDefaultSelectionMode : mWriteRulesSelectionMode;
            Set<ContentRule> selectedSet = mIsDefaultRules ? mWriteDefaultSelectedRules : mWriteSelectedRules;
            boolean isSelected = selectedSet.contains(rule);

            if (selectionMode) {
                holder.layoutNormal.setVisibility(View.GONE);
                holder.layoutSelection.setVisibility(View.VISIBLE);
                holder.tvNameSel.setText(rule.name); holder.tvPatternSel.setText(rule.pattern);
                holder.tvRuleStatus.setText(rule.enabled ? "已启用" : "已禁用");
                holder.tvRuleStatus.setTextColor(rule.enabled ?
                        ContextCompat.getColor(WriteRulesDetailActivity.this, R.color.status_active) :
                        ContextCompat.getColor(WriteRulesDetailActivity.this, R.color.status_inactive));
                holder.cbSelected.setChecked(isSelected);
                holder.itemView.setOnClickListener(v -> {
                    if (isSelected) selectedSet.remove(rule); else selectedSet.add(rule);
                    notifyItemChanged(position);
                    if (mIsDefaultRules) updateWriteDefaultSelectedCount(); else updateWriteSelectedCount();
                });
                holder.itemView.setOnLongClickListener(null);
            } else {
                holder.layoutNormal.setVisibility(View.VISIBLE);
                holder.layoutSelection.setVisibility(View.GONE);
                holder.tvName.setText(rule.name); holder.tvPattern.setText(rule.pattern);
                holder.switchEnabled.setOnCheckedChangeListener(null);
                holder.switchEnabled.setChecked(rule.enabled);
                holder.switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
                    rule.enabled = checked;
                    // 用户操作开关，保存并广播
                    if (mIsDefaultRules) saveDefaultWriteRules();
                    else saveWriteRules();
                });
                holder.btnDelete.setVisibility(rule.isDefault ? View.GONE : View.VISIBLE);
                holder.btnEdit.setVisibility(rule.isDefault ? View.GONE : View.VISIBLE);
                holder.btnEdit.setOnClickListener(v -> showEditRuleDialog(rule, true));
                holder.btnDelete.setOnClickListener(v -> { if (!mIsDefaultRules) deleteWriteRule(rule); });
                if (mIsDefaultRules) {
                    holder.itemView.setOnClickListener(v -> showViewRuleDialog(rule));
                    holder.itemView.setOnLongClickListener(v -> { enterWriteDefaultSelectionMode(rule); return true; });
                } else {
                    holder.itemView.setOnClickListener(null);
                    holder.itemView.setOnLongClickListener(v -> { enterWriteSelectionMode(rule); return true; });
                }
            }
        }

        @Override public int getItemCount() { return mRulesList.size(); }
        void refreshSelectionMode() { notifyItemRangeChanged(0, getItemCount()); }
    
        class WriteRuleViewHolder extends RecyclerView.ViewHolder {
            View layoutNormal; SwitchCompat switchEnabled; TextView tvName, tvPattern; View btnEdit, btnDelete;
            View layoutSelection; CheckBox cbSelected; TextView tvNameSel, tvPatternSel, tvRuleStatus;
            WriteRuleViewHolder(View itemView) {
                super(itemView);
                layoutNormal = itemView.findViewById(R.id.layout_normal);
                switchEnabled = itemView.findViewById(R.id.switch_rule_enabled);
                tvName = itemView.findViewById(R.id.tv_rule_name);
                tvPattern = itemView.findViewById(R.id.tv_rule_pattern);
                btnEdit = itemView.findViewById(R.id.btn_rule_edit);
                btnDelete = itemView.findViewById(R.id.btn_rule_delete);
                layoutSelection = itemView.findViewById(R.id.layout_selection);
                cbSelected = itemView.findViewById(R.id.cb_rule_selected);
                tvNameSel = itemView.findViewById(R.id.tv_rule_name_sel);
                tvPatternSel = itemView.findViewById(R.id.tv_rule_pattern_sel);
                tvRuleStatus = itemView.findViewById(R.id.tv_rule_status);
            }
        }
    }

    private void refreshWriteRulesAdapter() {
        if (mWriteRulesAdapter != null) {
            mWriteRulesAdapter.refreshSelectionMode();
        }
    }

    private void refreshWriteDefaultRulesAdapter() {
        if (mWriteDefaultRulesAdapter != null) {
            mWriteDefaultRulesAdapter.refreshSelectionMode();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期清理
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
        if (mCurrentRuleDialog != null && mCurrentRuleDialog.isShowing()) mCurrentRuleDialog.dismiss();
        super.onDestroy();
    }
}
