package com.android.clipboardguard;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志列表适配器
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {

    // 静态共享格式化器（线程内安全，RecyclerView 回调均在主线程）
    private static final SimpleDateFormat SDF_TIME = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private List<LogEntry> mLogs = new ArrayList<>();
    private OnLogClickListener mListener;

    public interface OnLogClickListener {
        void onLogClick(LogEntry log);
    }

    public void setOnLogClickListener(OnLogClickListener listener) {
        mListener = listener;
    }

    public void setLogs(List<LogEntry> logs) {
        mLogs = logs != null ? logs : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void addLog(LogEntry log) {
        mLogs.add(0, log);
        notifyItemInserted(0);
    }

    public void clear() {
        mLogs.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogEntry log = mLogs.get(position);
        holder.bind(log);
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) mListener.onLogClick(log);
        });
    }

    @Override
    public int getItemCount() {
        return mLogs.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvTime, tvAppName, tvContent, tvAction;

        LogViewHolder(@NonNull View v) {
            super(v);
            tvTime    = v.findViewById(R.id.tv_time);
            tvAppName = v.findViewById(R.id.tv_app_name);
            tvContent = v.findViewById(R.id.tv_content);
            tvAction  = v.findViewById(R.id.tv_action);
        }

        void bind(LogEntry log) {
            tvTime.setText(SDF_TIME.format(new Date(log.timestamp)));

            // 尝试显示应用名，fallback 包名
            if (log.packageName != null) {
                try {
                    PackageManager pm = itemView.getContext().getPackageManager();
                    ApplicationInfo ai = pm.getApplicationInfo(log.packageName, 0);
                    tvAppName.setText(pm.getApplicationLabel(ai));
                } catch (PackageManager.NameNotFoundException e) {
                    tvAppName.setText(log.packageName);
                }
            } else {
                tvAppName.setText("未知");
            }

            // 动作标签颜色
            tvAction.setText(log.action);
            tvAction.setTextColor("拦截".equals(log.action) ? 0xFFE53935 : 0xFF43A047);

            // 内容预览（最多 50 字）
            if (log.content != null && !log.content.isEmpty()) {
                tvContent.setVisibility(View.VISIBLE);
                tvContent.setText(log.content.length() > 50
                        ? log.content.substring(0, 50) + "..."
                        : log.content);
            } else {
                tvContent.setVisibility(View.GONE);
            }
        }
    }
}
