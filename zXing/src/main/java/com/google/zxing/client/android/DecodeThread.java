/*
 * Copyright (C) 2008 ZXing authors
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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.ResultPointCallback;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * This thread does all the heavy lifting of decoding the images.
 * 这个线程：主要的作用的，设置扫描的时候支持的识别哪几种的配置
 * @author dswitkin@google.com (Daniel Switkin)
 */
final class DecodeThread extends Thread {

  public static final String BARCODE_BITMAP = "barcode_bitmap";
  public static final String BARCODE_SCALED_FACTOR = "barcode_scaled_factor";

  private final CaptureActivity activity;
  private final Map<DecodeHintType,Object> hints;
  private Handler handler;
  private final CountDownLatch handlerInitLatch;

  DecodeThread(CaptureActivity activity,
               Collection<BarcodeFormat> decodeFormats,
               Map<DecodeHintType,?> baseHints,
               String characterSet,
               ResultPointCallback resultPointCallback) {

    this.activity = activity;
    handlerInitLatch = new CountDownLatch(1);

    hints = new EnumMap<>(DecodeHintType.class);
    if (baseHints != null) {
      hints.putAll(baseHints);
    }

    // The prefs can't change while the thread is running, so pick them up once here.
    if (decodeFormats == null || decodeFormats.isEmpty()) {
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
      decodeFormats = EnumSet.noneOf(BarcodeFormat.class);
      // 获取seting中一维码-商业的设置
      if (prefs.getBoolean(PreferencesActivity.KEY_DECODE_1D_PRODUCT, true)) {
        decodeFormats.addAll(DecodeFormatManager.PRODUCT_FORMATS);
      }
      // 获取seting  中一维码-工业的设置
      if (prefs.getBoolean(PreferencesActivity.KEY_DECODE_1D_INDUSTRIAL, true)) {
        decodeFormats.addAll(DecodeFormatManager.INDUSTRIAL_FORMATS);
      }
      // 获取seting中二维码的设置
      if (prefs.getBoolean(PreferencesActivity.KEY_DECODE_QR, true)) {
        decodeFormats.addAll(DecodeFormatManager.QR_CODE_FORMATS);
      }
      // 获取seting中国DATA MATRIX 的设置
      if (prefs.getBoolean(PreferencesActivity.KEY_DECODE_DATA_MATRIX, true)) {
        decodeFormats.addAll(DecodeFormatManager.DATA_MATRIX_FORMATS);
      }
      //获取设置seting中的aztec 的设置
      if (prefs.getBoolean(PreferencesActivity.KEY_DECODE_AZTEC, false)) {
        decodeFormats.addAll(DecodeFormatManager.AZTEC_FORMATS);
      }
      // 获取设置seting的pdf设置
      if (prefs.getBoolean(PreferencesActivity.KEY_DECODE_PDF417, false)) {
        decodeFormats.addAll(DecodeFormatManager.PDF417_FORMATS);
      }
    }
    hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);

    if (characterSet != null) {
      hints.put(DecodeHintType.CHARACTER_SET, characterSet);
    }
    hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, resultPointCallback);
  }

  Handler getHandler() {
    try {
      handlerInitLatch.await();
    } catch (InterruptedException ie) {
      // continue?
    }
    return handler;
  }

  @Override
  public void run() {
    Looper.prepare();
    handler = new DecodeHandler(activity, hints);
    handlerInitLatch.countDown();
    Looper.loop();
  }

}
