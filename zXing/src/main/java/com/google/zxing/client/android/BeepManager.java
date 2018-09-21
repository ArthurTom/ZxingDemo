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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;

/**
 * 功能：声音和震动
 * Manages beeps and vibrations for {@link CaptureActivity}.
 */
final class BeepManager implements MediaPlayer.OnErrorListener, Closeable {

    private static final String TAG = BeepManager.class.getSimpleName();

    private static final float BEEP_VOLUME = 0.10f;
    private static final long VIBRATE_DURATION = 200L;

    private final Activity activity;
    private MediaPlayer mediaPlayer;
    private boolean playBeep;     // 是否开启声音模式
    private boolean vibrate;

    BeepManager(Activity activity) {  // 构造方法
        this.activity = activity;
        this.mediaPlayer = null;
        updatePrefs();
    }

    /**
     * 同步方法
     */
    synchronized void updatePrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);  // 获取存储的数据
        playBeep = shouldBeep(prefs, activity);    // 返回是否开启了声音
        vibrate = prefs.getBoolean(PreferencesActivity.KEY_VIBRATE, false);  // 获取震动的状态
        if (playBeep && mediaPlayer == null) {  // 如果是声音模式&&播放器是空的清空下
            // The volume on STREAM_SYSTEM is not adjustable, and users found it too loud,
            // so we now play on the music stream.
            activity.setVolumeControlStream(AudioManager.STREAM_MUSIC);  // 设置媒体的音量
            mediaPlayer = buildMediaPlayer(activity);                    // 实例化一个媒体播放器
        }
    }

    synchronized void playBeepSoundAndVibrate() {
        if (playBeep && mediaPlayer != null) {
            mediaPlayer.start();
        }
        if (vibrate) {
            Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(VIBRATE_DURATION);
        }
    }

    /**
     * @param prefs  sharepreference 对象
     * @param activity
     * @return   是否开启声音
     */
    private static boolean shouldBeep(SharedPreferences prefs, Context activity) {
        // 获取存储中是否设置提示音
        boolean shouldPlayBeep = prefs.getBoolean(PreferencesActivity.KEY_PLAY_BEEP, true);
        if (shouldPlayBeep) {  //如果设置了提示音
            // See if sound settings overrides this，获取音频服务
            AudioManager audioService = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            // 传统模式：在音声下有声音，在震动下只有震动
            if (audioService.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {   // 不是这种模式
                shouldPlayBeep = false;  // 返回false
            }
        }
        return shouldPlayBeep;
    }

    /**
     * 功能：
     * @param activity
     * @return  播放器对象
     */
    private MediaPlayer buildMediaPlayer(Context activity) {
        MediaPlayer mediaPlayer = new MediaPlayer();   // 多媒体对象
        // 获取assets文件下的beep音频文件
        try (AssetFileDescriptor file = activity.getResources().openRawResourceFd(R.raw.beep)) {
            //播放器设置播放路径和 播放时间和播放时间
            mediaPlayer.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            mediaPlayer.setOnErrorListener(this);  // 注册错误监听事件
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);  //设置媒体播放的类型
            mediaPlayer.setLooping(false); //是否循环播放
            mediaPlayer.setVolume(BEEP_VOLUME, BEEP_VOLUME);  //设置左右声道的大小
            mediaPlayer.prepare();   // 准备播放
            return mediaPlayer;      // 返回播放器
        } catch (IOException ioe) {
            Log.w(TAG, ioe);
            mediaPlayer.release();  // 释放掉播放器
            return null;
        }
    }

    //  MediaPlayer.OnErrorListener 中的抽象方法
    @Override
    public synchronized boolean onError(MediaPlayer mp, int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            // we are finished, so put up an appropriate error toast if required and finish
            activity.finish();
        } else {
            // possibly media player error, so release and recreate
            close();
            updatePrefs();
        }
        return true;
    }

    //  Closeable 中的方法
    @Override
    public synchronized void close() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

}
