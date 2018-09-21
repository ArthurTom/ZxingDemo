/*
 * Copyright (C) 2012 ZXing authors
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

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.preference.PreferenceManager;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.camera.FrontLightMode;

/**
 * Detects ambient light and switches on the front light when very dark, and off again when sufficiently light.
 *  功能：闪光灯功能   实现重力传感监听
 * @author Sean Owen
 * @author Nikolaus Huber
 */
final class AmbientLightManager implements SensorEventListener {

  private static final float TOO_DARK_LUX = 45.0f;
  private static final float BRIGHT_ENOUGH_LUX = 450.0f;

  private final Context context;
  private CameraManager cameraManager;
  private Sensor lightSensor;

  AmbientLightManager(Context context) {
    this.context = context;
  }

  /**
   * 功能：将CameraManager引入
   * @param cameraManager   CameraManager对象
   */
  void start(CameraManager cameraManager) {
    this.cameraManager = cameraManager;
    SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);  // 获取数据存储
    // 调用readPref（）返回setting设置的闪光灯的设置状态
    if (FrontLightMode.readPref(sharedPrefs) == FrontLightMode.AUTO) {  // 闪光灯如果是自动开启,则需要配置传感器
      // 传感器管理器
      SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
      //获取光线感应传感器
      lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
      if (lightSensor != null) {
        //传感器监听注册--指定光线传感器 ，传感器延迟感应200ms
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
      }
    }
  }

  void stop() {
    if (lightSensor != null) {
      SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
      sensorManager.unregisterListener(this);
      cameraManager = null;
      lightSensor = null;
    }
  }

  @Override
  public void onSensorChanged(SensorEvent sensorEvent) {
    float ambientLightLux = sensorEvent.values[0];
    if (cameraManager != null) {
      if (ambientLightLux <= TOO_DARK_LUX) {
        cameraManager.setTorch(true);
      } else if (ambientLightLux >= BRIGHT_ENOUGH_LUX) {
        cameraManager.setTorch(false);
      }
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    // do nothing
  }

}
