/*
 * Copyright (C) 2010 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.util.Log;

import java.util.concurrent.RejectedExecutionException;

/**
 * Finishes an activity after a period of inactivity if the device is on battery power.
 * 功能：界面电量的显示
 */
final class InactivityTimer {

    private static final String TAG = InactivityTimer.class.getSimpleName();

    private static final long INACTIVITY_DELAY_MS = 5 * 60 * 1000L;

    private final Activity activity;
    private final BroadcastReceiver powerStatusReceiver;    //电源广播
    private boolean registered;                            // 标记是否注册了
    private AsyncTask<Object, Object, Object> inactivityTask;

    InactivityTimer(Activity activity) {    //
        this.activity = activity;
        powerStatusReceiver = new PowerStatusReceiver();  // 实例化广播接收器
        registered = false;                               // 广播还未注册
        onActivity();
    }

    synchronized void onActivity() {  // 同步锁方法
        cancel();
        inactivityTask = new InactivityAsyncTask();      // 实例化异步
        try {
            inactivityTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);  // 开启并行处理
        } catch (RejectedExecutionException ree) {
            Log.w(TAG, "Couldn't schedule inactivity task; ignoring");
        }
    }

    synchronized void onPause() {
        cancel();
        if (registered) {
            activity.unregisterReceiver(powerStatusReceiver);
            registered = false;
        } else {
            Log.w(TAG, "PowerStatusReceiver was never registered?");
        }
    }

    synchronized void onResume() {
        if (registered) {   // 已经注册
            Log.w(TAG, "PowerStatusReceiver was already registered?");
        } else {  // 没有注册，电池广播动态注册监听
            activity.registerReceiver(powerStatusReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            registered = true;  // 将监听状态设置为监听
        }
        onActivity();
    }

    /**
     * 取消异步
     */
    private synchronized void cancel() {
        AsyncTask<?, ?, ?> task = inactivityTask;
        if (task != null) {
            task.cancel(true);
            inactivityTask = null;
        }
    }

    void shutdown() {
        cancel();
    }

    /**
     * 内部类：广播接收器
     */
    private final class PowerStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                // 0 indicates that we're on battery
                //当前的电池使用的是什么的电源，0代表电池，其他是其他的充电
                boolean onBatteryNow = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) <= 0;
                if (onBatteryNow) {
                    InactivityTimer.this.onActivity();
                } else {
                    InactivityTimer.this.cancel();
                }
            }
        }
    }

    /**
     * 内部类，实现异步
     */
    private final class InactivityAsyncTask extends AsyncTask<Object, Object, Object> {
        @Override
        protected Object doInBackground(Object... objects) {
            try {
                Thread.sleep(INACTIVITY_DELAY_MS);   // 延时5分钟
                Log.i(TAG, "Finishing activity due to inactivity");
                activity.finish();                  // 将扫描界面的activity 关闭
            } catch (InterruptedException e) {
                // continue without killing
            }
            return null;
        }
    }

}
