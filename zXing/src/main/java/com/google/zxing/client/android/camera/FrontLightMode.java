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

package com.google.zxing.client.android.camera;

import android.content.SharedPreferences;

import com.google.zxing.client.android.PreferencesActivity;

/**
 * Enumerates settings of the preference controlling the front light.
 * 枚举：闪光灯的三种状态
 */
public enum FrontLightMode {

    /**
     * Always on.
     * 译文：总是开启
     */
    ON,
    /**
     * On only when ambient light is low.
     * 只有只有当光线暗的时候，开启
     */
    AUTO,
    /**
     * Always off.
     * 总是关闭
     */
    OFF;

    /**
     * 功能：分析是那种模式
     * @param modeString  模式
     * @return  闪光灯的状态
     */
    private static FrontLightMode parse(String modeString) {
        return modeString == null ? OFF : valueOf(modeString);
    }

    /**
     * @param sharedPrefs  闪光灯设置的数据
     * @return
     */
    public static FrontLightMode readPref(SharedPreferences sharedPrefs) {
        // 获取setting中闪光灯是否设置，调用parse方法返回模式
        return parse(sharedPrefs.getString(PreferencesActivity.KEY_FRONT_LIGHT_MODE, OFF.toString()));
    }

}
