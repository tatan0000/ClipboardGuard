package com.android.clipboardguard;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权限弹窗结果接收器
 * 轮询 ContentProvider pending 表，等待 PermissionDialogActivity 写入结果
 */
public class PermissionResultReceiver {

    private static final String TAG            = "ClipboardGuard.Result";
    private static final long   POLL_INTERVAL  = 150; // ms
    private static final long   POLL_TIMEOUT   = 7_000; // ms

    private final String        mPackageName;
    private final CountDownLatch mLatch;
    private final AtomicInteger mResult;

    public PermissionResultReceiver(String packageName, CountDownLatch latch, AtomicInteger result) {
        this.mPackageName = packageName;
        this.mLatch       = latch;
        this.mResult      = result;
    }

    /** 启动后台轮询线程，有结果或超时后唤醒 latch */
    public void register(Context context) {
        Thread t = new Thread(() -> {
            Uri uri = Uri.parse("content://" + PermissionProvider.AUTHORITY + "/pending/" + mPackageName);
            long deadline = System.currentTimeMillis() + POLL_TIMEOUT;

            while (System.currentTimeMillis() < deadline) {
                try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(PermissionProvider.COL_DECISION);
                        if (idx >= 0) {
                            mResult.set(c.getInt(idx));
                            Log.i(TAG, "收到结果: " + mPackageName + " -> " + mResult.get());
                            // 删除 pending 记录
                            context.getContentResolver().delete(uri, null, null);
                            mLatch.countDown();
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "轮询异常: " + e.getMessage());
                }

                try { Thread.sleep(POLL_INTERVAL); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Log.w(TAG, "等待结果超时: " + mPackageName);
            mLatch.countDown();
        }, "ClipboardGuard-Poll-" + mPackageName);
        t.setDaemon(true);
        t.start();
    }
}
