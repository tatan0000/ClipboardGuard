package com.android.clipboardguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权限弹窗结果接收器
 * 等待 PermissionDialogActivity 通过 ContentProvider 写入结果后回调
 */
public class PermissionResultReceiver {

    private static final String TAG = "ClipboardGuard.Result";
    private static final String ACTION_RESULT = "com.android.clipboardguard.ACTION_PERMISSION_RESULT";

    private final String mPackageName;
    private final CountDownLatch mLatch;
    private final AtomicInteger mResult;

    public PermissionResultReceiver(String packageName, CountDownLatch latch, AtomicInteger result) {
        this.mPackageName = packageName;
        this.mLatch = latch;
        this.mResult = result;
    }

    /**
     * 注册结果接收器（轮询方式）
     */
    public void register(Context context) {
        // 使用 ContentProvider 轮询方式等待结果
        // 启动后台线程轮询 pending 表
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long timeout = 7000; // 7秒超时

            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    // 通过 ContentProvider 查询 pending 表
                    android.net.Uri uri = android.net.Uri.parse("content://com.android.clipboardguard.provider/pending/" + mPackageName);
                    try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int decisionIdx = cursor.getColumnIndex("decision");
                            if (decisionIdx >= 0) {
                                int decision = cursor.getInt(decisionIdx);
                                mResult.set(decision);
                                Log.i(TAG, "收到结果: " + mPackageName + " -> " + decision);

                                // 删除 pending 记录
                                context.getContentResolver().delete(uri, null, null);

                                // 唤醒主线程
                                mLatch.countDown();
                                return;
                            }
                        }
                    }
                    Thread.sleep(200); // 200ms 轮询间隔
                } catch (Exception e) {
                    Log.e(TAG, "轮询结果异常: " + e.getMessage());
                }
            }

            // 超时，唤醒主线程
            Log.w(TAG, "等待结果超时: " + mPackageName);
            mLatch.countDown();
        }).start();
    }

    /**
     * 取消注册（如果需要）
     */
    public void unregister(Context context) {
        // 轮询方式无需显式取消
    }
}