/*
 * Copyright (C) 2013 ZXing authors
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

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Implements support for barcode scanning preferences.
 *
 * @see PreferencesActivity
 */
public final class PreferencesFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private CheckBoxPreference[] checkBoxPrefs;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.preferences);   // 设置的布局，固定的用法

        PreferenceScreen preferences = getPreferenceScreen();  //获取pregerence 对象
        preferences.getSharedPreferences().registerOnSharedPreferenceChangeListener(this);  // 注册监听

        checkBoxPrefs = findDecodePrefs(preferences,
                PreferencesActivity.KEY_DECODE_1D_PRODUCT,  // 一维码
                PreferencesActivity.KEY_DECODE_1D_INDUSTRIAL,  // 一维码
                PreferencesActivity.KEY_DECODE_QR,             // 二维码
                PreferencesActivity.KEY_DECODE_DATA_MATRIX,    // data matrix
                PreferencesActivity.KEY_DECODE_AZTEC,         // aztec
                PreferencesActivity.KEY_DECODE_PDF417);       // pdf417

        disableLastCheckedPref();

        EditTextPreference customProductSearch = (EditTextPreference)
                preferences.findPreference(PreferencesActivity.KEY_CUSTOM_PRODUCT_SEARCH);
        customProductSearch.setOnPreferenceChangeListener(new CustomSearchURLValidator());
    }

    /**
     * @param preferences  对象
     * @param keys         xml中的条码类型的关键字
     * @return             条形码数组
     */
    private static CheckBoxPreference[] findDecodePrefs(PreferenceScreen preferences, String... keys) {
        CheckBoxPreference[] prefs = new CheckBoxPreference[keys.length];  // 数据
        for (int i = 0; i < keys.length; i++) {  // 遍历将条码类型添加进数组中去
            prefs[i] = (CheckBoxPreference) preferences.findPreference(keys[i]);
        }
        return prefs;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        disableLastCheckedPref();
    }

    private void disableLastCheckedPref() {
        Collection<CheckBoxPreference> checked = new ArrayList<>(checkBoxPrefs.length); // 新建一个数组，长度是条码类型长度
        for (CheckBoxPreference pref : checkBoxPrefs) {   // 遍历条码选中的数组
            if (pref.isChecked()) {  // 如果是其中有选中的，则添加进新数组中
                checked.add(pref);
            }
        }
        boolean disable = checked.size() <= 1;        //  选中的个数小于等于1
        for (CheckBoxPreference pref : checkBoxPrefs) {    // 循环的遍历
            pref.setEnabled(!(disable && checked.contains(pref)));  // 价格
        }
    }

    private final class CustomSearchURLValidator implements Preference.OnPreferenceChangeListener {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (!isValid(newValue)) {
                AlertDialog.Builder builder =
                        new AlertDialog.Builder(PreferencesFragment.this.getActivity());
                builder.setTitle(R.string.msg_error);
                builder.setMessage(R.string.msg_invalid_value);
                builder.setCancelable(true);
                builder.show();
                return false;
            }
            return true;
        }

        private boolean isValid(Object newValue) {
            // Allow empty/null value
            if (newValue == null) {
                return true;
            }
            String valueString = newValue.toString();
            if (valueString.isEmpty()) {
                return true;
            }
            // Before validating, remove custom placeholders, which will not
            // be considered valid parts of the URL in some locations:
            // Blank %t and %s:
            valueString = valueString.replaceAll("%[st]", "");
            // Blank %f but not if followed by digit or a-f as it may be a hex sequence
            valueString = valueString.replaceAll("%f(?![0-9a-f])", "");
            // Require a scheme otherwise:
            try {
                URI uri = new URI(valueString);
                return uri.getScheme() != null;
            } catch (URISyntaxException use) {
                return false;
            }
        }
    }

}
