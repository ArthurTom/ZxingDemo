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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.clipboard.ClipboardInterface;
import com.google.zxing.client.android.history.HistoryActivity;
import com.google.zxing.client.android.history.HistoryItem;
import com.google.zxing.client.android.history.HistoryManager;
import com.google.zxing.client.android.result.ResultButtonListener;
import com.google.zxing.client.android.result.ResultHandler;
import com.google.zxing.client.android.result.ResultHandlerFactory;
import com.google.zxing.client.android.result.supplement.SupplementalInfoRetriever;
import com.google.zxing.client.android.share.ShareActivity;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = CaptureActivity.class.getSimpleName();

    private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
    private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

    private static final String[] ZXING_URLS = {"http://zxing.appspot.com/scan", "zxing://scan/"};

    private static final int HISTORY_REQUEST_CODE = 0x0000bacc;

    private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES =
            EnumSet.of(ResultMetadataType.ISSUE_NUMBER,
                    ResultMetadataType.SUGGESTED_PRICE,
                    ResultMetadataType.ERROR_CORRECTION_LEVEL,
                    ResultMetadataType.POSSIBLE_COUNTRY);

    private CameraManager cameraManager;
    private CaptureActivityHandler handler;
    private Result savedResultToShow;
    private ViewfinderView viewfinderView;
    private TextView statusView;
    private View resultView;
    private Result lastResult;
    private boolean hasSurface;
    private boolean copyToClipboard;
    private IntentSource source;         // 跳转的类型
    private String sourceUrl;
    private ScanFromWebPageManager scanFromWebPageManager;
    private Collection<BarcodeFormat> decodeFormats;
    private Map<DecodeHintType, ?> decodeHints;
    private String characterSet;
    private HistoryManager historyManager;
    private InactivityTimer inactivityTimer;
    private BeepManager beepManager;
    private AmbientLightManager ambientLightManager;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);  // 屏幕高亮不息屏
        setContentView(R.layout.capture);

        hasSurface = false;
        inactivityTimer = new InactivityTimer(this);   // 电量低或者长时间未操作，自动关闭当前的CaptureActivity
        beepManager = new BeepManager(this);   // 扫描的时候发出的声音和震动效果
        ambientLightManager = new AmbientLightManager(this);  //控制感光原件，闪光灯的开关

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false); // 获取preference中的值
    }

    @Override
    protected void onResume() {
        super.onResume();

        // historyManager must be initialized here to update the history preference
        historyManager = new HistoryManager(this);    // 初始化历史管理器，获取其是否开启了setting的历史记录
        historyManager.trimHistory();                        // 数据库删除超过2000个数量的数据

        // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
        // want to open the camera driver and measure the screen size if we're going to show the help on
        // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
        // off screen.
        cameraManager = new CameraManager(getApplication());    // 摄像头的相关配置的初始化

        viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);   // 扫描窗口
        viewfinderView.setCameraManager(cameraManager);   // 将CameraManager对象引入到扫描控件中

        resultView = findViewById(R.id.result_view);  // 扫描后的结果块linearlayout
        statusView = (TextView) findViewById(R.id.status_view);  // 底部的提示块

        handler = null;
        lastResult = null;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        //获取setting中设置的不自动旋转的的状态
        if (prefs.getBoolean(PreferencesActivity.KEY_DISABLE_AUTO_ORIENTATION, true)) {
            setRequestedOrientation(getCurrentOrientation());  // 获取当前屏的方向，并设置屏幕方向
        } else {  // 假，设置横屏
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        resetStatusView();  // 重置UI界面的状态


        beepManager.updatePrefs();  // 媒体播放器的设置，获取setting的设置，初始化相应的一系列对象

        ambientLightManager.start(cameraManager);  // 闪光灯的配置，通过seting的配置，配置光线传感器的相关配置

        inactivityTimer.onResume();    // 电量监听，是否关闭手机屏幕

        Intent intent = getIntent();
        // setting 中是否复制到粘贴板&&是否存入到历史中
        copyToClipboard = prefs.getBoolean(PreferencesActivity.KEY_COPY_TO_CLIPBOARD, true)
                && (intent == null || intent.getBooleanExtra(Intents.Scan.SAVE_HISTORY, true));

        source = IntentSource.NONE;    // 跳转的方向
        sourceUrl = null;
        scanFromWebPageManager = null;
        decodeFormats = null;
        characterSet = null;

        if (intent != null) {

            String action = intent.getAction();
            String dataString = intent.getDataString();

            if (Intents.Scan.ACTION.equals(action)) {    // 如果当前的动作是扫描动作

                // Scan the formats the intent requested, and return the result to the calling activity.
                source = IntentSource.NATIVE_APP_INTENT;
                decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);
                decodeHints = DecodeHintManager.parseDecodeHints(intent);
                // 获取扫描框的大小
                if (intent.hasExtra(Intents.Scan.WIDTH) && intent.hasExtra(Intents.Scan.HEIGHT)) {
                    int width = intent.getIntExtra(Intents.Scan.WIDTH, 0);
                    int height = intent.getIntExtra(Intents.Scan.HEIGHT, 0);
                    if (width > 0 && height > 0) {
                        cameraManager.setManualFramingRect(width, height);
                        Log.d("zwk", "默认的---widht的大小:" + width + "----height的大小" + height);
                    }
                }

                if (intent.hasExtra(Intents.Scan.CAMERA_ID)) {
                    int cameraId = intent.getIntExtra(Intents.Scan.CAMERA_ID, -1);
                    if (cameraId >= 0) {
                        cameraManager.setManualCameraId(cameraId);
                    }
                }

                String customPromptMessage = intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE);
                if (customPromptMessage != null) {
                    statusView.setText(customPromptMessage);
                }

            } else if (dataString != null &&
                    dataString.contains("http://www.google") &&
                    dataString.contains("/m/products/scan")) {
                // Scan only products and send the result to mobile Product Search.
                source = IntentSource.PRODUCT_SEARCH_LINK;
                sourceUrl = dataString;
                decodeFormats = DecodeFormatManager.PRODUCT_FORMATS;

            } else if (isZXingURL(dataString)) {

                // Scan formats requested in query string (all formats if none specified).
                // If a return URL is specified, send the results there. Otherwise, handle it ourselves.
                source = IntentSource.ZXING_LINK;
                sourceUrl = dataString;
                Uri inputUri = Uri.parse(dataString);
                scanFromWebPageManager = new ScanFromWebPageManager(inputUri);
                decodeFormats = DecodeFormatManager.parseDecodeFormats(inputUri);
                // Allow a sub-set of the hints to be specified by the caller.
                decodeHints = DecodeHintManager.parseDecodeHints(inputUri);

            }

            characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);

        }

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);     // 预览界面
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        if (hasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(surfaceHolder);
        } else {
            // Install the callback and wait for surfaceCreated() to init the camera.
            surfaceHolder.addCallback(this);
        }
    }

    @Override
    protected void onPause() {
        if (handler != null) {
            handler.quitSynchronously();
            handler = null;
        }
        inactivityTimer.onPause();
        ambientLightManager.stop();
        beepManager.close();
        cameraManager.closeDriver();
        //historyManager = null; // Keep for onActivityResult
        if (!hasSurface) {
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);  // 预览窗口
            SurfaceHolder surfaceHolder = surfaceView.getHolder();
            surfaceHolder.removeCallback(this);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        inactivityTimer.shutdown();
        super.onDestroy();
    }

    ViewfinderView getViewfinderView() {
        return viewfinderView;
    }

    public Handler getHandler() {
        return handler;
    }

    CameraManager getCameraManager() {
        return cameraManager;
    }

    /**
     * 获取当前屏幕的状态方向
     *
     * @return 返回当前手机的方向
     */
    private int getCurrentOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();  // 获取屏幕旋转的方向
        // 获取手机的方向==横向
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_90:
                    return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
        } else {  // 手机方向==竖屏
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_270:
                    return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            }
        }
    }

    private static boolean isZXingURL(String dataString) {
        if (dataString == null) {
            return false;
        }
        for (String url : ZXING_URLS) {
            if (dataString.startsWith(url)) {
                return true;
            }
        }
        return false;
    }

    // 手机操作监听
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:     // 返回键
                if (source == IntentSource.NATIVE_APP_INTENT) {
                    setResult(RESULT_CANCELED);
                    finish();
                    return true;
                }
                if ((source == IntentSource.NONE || source == IntentSource.ZXING_LINK) && lastResult != null) {
                    restartPreviewAfterDelay(0L);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_FOCUS:
            case KeyEvent.KEYCODE_CAMERA:
                // Handle these events so they don't launch the Camera app
                return true;
            // Use volume up/down to turn on light
            case KeyEvent.KEYCODE_VOLUME_DOWN:   //  音量-
                cameraManager.setTorch(false);    // 关闭闪光灯
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:  // 音量+事件
                cameraManager.setTorch(true);  // 打开闪光灯
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.capture, menu);
        return super.onCreateOptionsMenu(menu);
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intents.FLAG_NEW_DOC);
        switch (item.getItemId()) {
            case R.id.menu_share:  // 顶部菜单分享功能
                intent.setClassName(this, ShareActivity.class.getName());
                startActivity(intent);
                break;
            case R.id.menu_history:  // 历史
                intent.setClassName(this, HistoryActivity.class.getName());
                startActivityForResult(intent, HISTORY_REQUEST_CODE);
                break;
            case R.id.menu_settings:  //设置
                intent.setClassName(this, PreferencesActivity.class.getName());
                startActivity(intent);
                break;
            case R.id.menu_help: // 帮助
                intent.setClassName(this, HelpActivity.class.getName());
                startActivity(intent);
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /*
     *历史记录回调
     *
     * */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == RESULT_OK && requestCode == HISTORY_REQUEST_CODE && historyManager != null) {
            int itemNumber = intent.getIntExtra(Intents.History.ITEM_NUMBER, -1);
            if (itemNumber >= 0) {
                // 通过历史管理，在数据看查找数据内容，返回点击的历史记录的内容
                HistoryItem historyItem = historyManager.buildHistoryItem(itemNumber);
                //通过这个方法将内容展示到界面中
                //       decodeOrStoreSavedBitmap(null, historyItem.getResult());
            }
        }
    }

    /**
     * 功能:处理图片
     *
     * @param bitmap 图片
     * @param result 扫描的结果
     */
    private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
        // Bitmap isn't used yet -- will be used soon
        if (handler == null) {
            savedResultToShow = result;
        } else {
            if (result != null) {
                savedResultToShow = result;
            }
            if (savedResultToShow != null) {
                Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
                handler.sendMessage(message);
            }
            savedResultToShow = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!hasSurface) {
            hasSurface = true;
            initCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // do nothing
    }

    /**
     * A valid barcode has been found, so give an indication of success and show the results.
     * 功能：这个方法是返回的二维码扫描后的结果
     *  将扫描结果进行分类处理
     *
     * @param rawResult   The contents of the barcode.   扫描二维码的获得的结果
     * @param scaleFactor amount by which thumbnail was scaled   获得的缩略图
     * @param barcode     A greyscale bitmap of the camera data which was decoded.  一个被相机编码的灰度位图
     */
    public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
        inactivityTimer.onActivity();
        lastResult = rawResult;
        Log.d("zwk", "扫描后返回的结果是：" + lastResult.toString());
        //
        ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult);

        boolean fromLiveScan = barcode != null;  // 判断缩略图是否存在
        if (fromLiveScan) {  // 缩略图存在
            historyManager.addHistoryItem(rawResult, resultHandler); // 存储到数据库中
            // Then not from history, so beep/vibrate and we have an image to draw on
            beepManager.playBeepSoundAndVibrate();       // 开启扫描完成的声音或者震动
            drawResultPoints(barcode, scaleFactor, rawResult);  // 为扫码结果的图片中的二维码的关键部位设置颜色
        }

        switch (source) {         // 分类处理
            case NATIVE_APP_INTENT:
            case PRODUCT_SEARCH_LINK:
                handleDecodeExternally(rawResult, resultHandler, barcode);  // 这两种模式，将结果返回给三方app调用者。
                break;
            case ZXING_LINK:
                if (scanFromWebPageManager == null || !scanFromWebPageManager.isScanFromWebPage()) {
                    handleDecodeInternally(rawResult, resultHandler, barcode);
                } else {
                    handleDecodeExternally(rawResult, resultHandler, barcode);
                }
                break;
            case NONE:   // 这种模式是，扫描模块直接开启，
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                //获取seting中是否是批量的扫描
                if (fromLiveScan && prefs.getBoolean(PreferencesActivity.KEY_BULK_MODE, false)) {
                    Toast.makeText(getApplicationContext(),
                            getResources().getString(R.string.msg_bulk_mode_scanned) + " (" + rawResult.getText() + ')',
                            Toast.LENGTH_SHORT).show();
                    maybeSetClipboard(resultHandler);
                    // Wait a moment or else it will scan the same barcode continuously about 3 times
                    restartPreviewAfterDelay(BULK_MODE_SCAN_DELAY_MS);
                } else {
                    // 由于是扫描模块自定的开启，所以调用handleDecodeInternally将结果展示出来
                    handleDecodeInternally(rawResult, resultHandler, barcode);
                }
                break;
        }
    }

    /**
     * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
     * 翻译：为扫描后获取的缩略图中添加一条绿色横线，或者为二维码图片的几个关键位置设置绿色的颜色
     *
     * @param barcode     A bitmap of the captured image.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param rawResult   The decoded results which contains the points to draw.
     */
    private void drawResultPoints(Bitmap barcode, float scaleFactor, Result rawResult) {
        ResultPoint[] points = rawResult.getResultPoints();
        if (points != null && points.length > 0) {
            Canvas canvas = new Canvas(barcode);
            Paint paint = new Paint();
            paint.setColor(getResources().getColor(R.color.result_points));
            if (points.length == 2) {
                paint.setStrokeWidth(4.0f);
                drawLine(canvas, paint, points[0], points[1], scaleFactor);
            } else if (points.length == 4 &&
                    (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A ||
                            rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
                // Hacky special case -- draw two lines, for the barcode and metadata
                drawLine(canvas, paint, points[0], points[1], scaleFactor);
                drawLine(canvas, paint, points[2], points[3], scaleFactor);
            } else {
                paint.setStrokeWidth(10.0f);
                for (ResultPoint point : points) {
                    if (point != null) {
                        canvas.drawPoint(scaleFactor * point.getX(), scaleFactor * point.getY(), paint);
                    }
                }
            }
        }
    }

    private static void drawLine(Canvas canvas, Paint paint, ResultPoint a, ResultPoint b, float scaleFactor) {
        if (a != null && b != null) {
            canvas.drawLine(scaleFactor * a.getX(),
                    scaleFactor * a.getY(),
                    scaleFactor * b.getX(),
                    scaleFactor * b.getY(),
                    paint);
        }
    }

    // Put up our own UI for how to handle the decoded contents.
    // 将扫描的结果展示到界面上
    private void handleDecodeInternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {

        Log.d("zwk", "展示内容" + rawResult.toString());
        maybeSetClipboard(resultHandler);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (resultHandler.getDefaultButtonID() != null && prefs.getBoolean(PreferencesActivity.KEY_AUTO_OPEN_WEB, false)) {
            resultHandler.handleButtonPress(resultHandler.getDefaultButtonID());
            return;
        }

        statusView.setVisibility(View.GONE);
        viewfinderView.setVisibility(View.GONE);
        resultView.setVisibility(View.VISIBLE);

        ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);  // 扫描后，显示截取的二维码的图片
        if (barcode == null) {    // 如果返回的截取的图片为null，则设置默认的图片
            barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
                    R.drawable.launcher_icon));
        } else { // 如果返回的截取的图片不为空，则将图片显示出来
            barcodeImageView.setImageBitmap(barcode);
        }

        TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
        Log.d("zwk", "---扫描的格式是---" + rawResult.getBarcodeFormat().toString());
        formatTextView.setText(rawResult.getBarcodeFormat().toString());    // 设置扫描结果，显示出来

        TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
        typeTextView.setText(resultHandler.getType().toString());           // 返回的二维码数据的文本类型

        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
        timeTextView.setText(formatter.format(rawResult.getTimestamp()));   // 返回二维码结果的时间


        TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
        View metaTextViewLabel = findViewById(R.id.meta_text_view_label);  // 元数据

        metaTextView.setVisibility(View.GONE);           // 元数据类别 隐藏
        metaTextViewLabel.setVisibility(View.GONE);      // 返回的元数据了 隐藏

        Map<ResultMetadataType, Object> metadata = rawResult.getResultMetadata();  // 返回
        if (metadata != null) {
            StringBuilder metadataText = new StringBuilder(20);
            for (Map.Entry<ResultMetadataType, Object> entry : metadata.entrySet()) {
                if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
                    metadataText.append(entry.getValue()).append('\n');
                }
            }
            if (metadataText.length() > 0) {
                metadataText.setLength(metadataText.length() - 1);
                metaTextView.setText(metadataText);       // 设置元数据
                Log.d("zwk", metadataText + "-----");
                metaTextView.setVisibility(View.VISIBLE);
                metaTextViewLabel.setVisibility(View.VISIBLE);
            }
        }

        CharSequence displayContents = resultHandler.getDisplayContents();
        TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
        contentsTextView.setText(displayContents);
        Log.d("zwk", "扫描后，二维码的内容" + displayContents);

        int scaledSize = Math.max(22, 32 - displayContents.length() / 4);
        contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);  //设置二维码内容的字体大小

        TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
        supplementTextView.setText("");
        supplementTextView.setOnClickListener(null);

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
            SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
                    resultHandler.getResult(),
                    historyManager,
                    this);
        }

        int buttonCount = resultHandler.getButtonCount();
        ViewGroup buttonView = (ViewGroup) findViewById(R.id.result_button_view);
        buttonView.requestFocus();
        for (int x = 0; x < ResultHandler.MAX_BUTTON_COUNT; x++) {
            TextView button = (TextView) buttonView.getChildAt(x);
            if (x < buttonCount) {
                button.setVisibility(View.VISIBLE);
                button.setText(resultHandler.getButtonText(x));
                button.setOnClickListener(new ResultButtonListener(resultHandler, x));
            } else {
                button.setVisibility(View.GONE);
            }
        }

    }

    // Briefly show the contents of the barcode, then handle the result outside Barcode Scanner.
    /*
        功能：将扫描的结果提供 ，返还给第三方app
     *source == IntentSource.NATIVE_APP_INTENT时，BS会将扫描分析的结果存到Intent中返回给调用方app，
     * 因此调用方app在启动BS的时候一定要使用startActivityForResult
     */

    private void handleDecodeExternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {
        if (barcode != null) {
            viewfinderView.drawResultBitmap(barcode);
        }

        long resultDurationMS;
        if (getIntent() == null) {
            resultDurationMS = DEFAULT_INTENT_RESULT_DURATION_MS;
        } else {
            resultDurationMS = getIntent().getLongExtra(Intents.Scan.RESULT_DISPLAY_DURATION_MS,
                    DEFAULT_INTENT_RESULT_DURATION_MS);
        }

        if (resultDurationMS > 0) {
            String rawResultString = String.valueOf(rawResult);
            Log.d("zwk", "rawResultString" + rawResultString);
            if (rawResultString.length() > 32) {
                rawResultString = rawResultString.substring(0, 32) + " ...";
            }
            statusView.setText(getString(resultHandler.getDisplayTitle()) + " : " + rawResultString);
        }

        maybeSetClipboard(resultHandler);

        switch (source) {   // 进行判断是那种模式
            case NATIVE_APP_INTENT:   // 如果是三方app,调用的，则需要使用intent返回给调用方
                Log.d("capture", "NATIVE_APP_INTENT");
                // Hand back whatever action they requested - this can be changed to Intents.Scan.ACTION when
                // the deprecated intent is retired.
                Intent intent = new Intent(getIntent().getAction());
                intent.addFlags(Intents.FLAG_NEW_DOC);
                intent.putExtra(Intents.Scan.RESULT, rawResult.toString());
                intent.putExtra(Intents.Scan.RESULT_FORMAT, rawResult.getBarcodeFormat().toString());
                byte[] rawBytes = rawResult.getRawBytes();
                if (rawBytes != null && rawBytes.length > 0) {
                    intent.putExtra(Intents.Scan.RESULT_BYTES, rawBytes);
                }
                Map<ResultMetadataType, ?> metadata = rawResult.getResultMetadata();
                if (metadata != null) {
                    if (metadata.containsKey(ResultMetadataType.UPC_EAN_EXTENSION)) {
                        intent.putExtra(Intents.Scan.RESULT_UPC_EAN_EXTENSION,
                                metadata.get(ResultMetadataType.UPC_EAN_EXTENSION).toString());
                    }
                    Number orientation = (Number) metadata.get(ResultMetadataType.ORIENTATION);
                    if (orientation != null) {
                        intent.putExtra(Intents.Scan.RESULT_ORIENTATION, orientation.intValue());
                    }
                    String ecLevel = (String) metadata.get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
                    if (ecLevel != null) {
                        intent.putExtra(Intents.Scan.RESULT_ERROR_CORRECTION_LEVEL, ecLevel);
                    }
                    @SuppressWarnings("unchecked")
                    Iterable<byte[]> byteSegments = (Iterable<byte[]>) metadata.get(ResultMetadataType.BYTE_SEGMENTS);
                    if (byteSegments != null) {
                        int i = 0;
                        for (byte[] byteSegment : byteSegments) {
                            intent.putExtra(Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + i, byteSegment);
                            i++;
                        }
                    }
                }
                sendReplyMessage(R.id.return_scan_result, intent, resultDurationMS);
                break;

            case PRODUCT_SEARCH_LINK:  // 如果是这种模式，这通过网络查询编号
                Log.d("capture", "PRODUCT_SEARCH_LINK");
                // Reformulate the URL which triggered us into a query, so that the request goes to the same
                // TLD as the scan URL.
                int end = sourceUrl.lastIndexOf("/scan");
                String productReplyURL = sourceUrl.substring(0, end) + "?q=" +
                        resultHandler.getDisplayContents() + "&source=zxing";
                sendReplyMessage(R.id.launch_product_query, productReplyURL, resultDurationMS);
                break;

            case ZXING_LINK:  // 同上
                Log.d("capture", "ZXING_LINK");
                if (scanFromWebPageManager != null && scanFromWebPageManager.isScanFromWebPage()) {
                    String linkReplyURL = scanFromWebPageManager.buildReplyURL(rawResult, resultHandler);
                    scanFromWebPageManager = null;
                    sendReplyMessage(R.id.launch_product_query, linkReplyURL, resultDurationMS);
                }
                break;
        }
    }

    private void maybeSetClipboard(ResultHandler resultHandler) {
        if (copyToClipboard && !resultHandler.areContentsSecure()) {
            ClipboardInterface.setText(resultHandler.getDisplayContents(), this);
        }
    }

    private void sendReplyMessage(int id, Object arg, long delayMS) {
        if (handler != null) {
            Message message = Message.obtain(handler, id, arg);
            if (delayMS > 0L) {
                handler.sendMessageDelayed(message, delayMS);
            } else {
                handler.sendMessage(message);
            }
        }
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (cameraManager.isOpen()) {
            Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        try {
            cameraManager.openDriver(surfaceHolder);
            // Creating the handler starts the preview, which can also throw a RuntimeException.
            if (handler == null) {
                handler = new CaptureActivityHandler(this, decodeFormats, decodeHints, characterSet, cameraManager);
            }
            decodeOrStoreSavedBitmap(null, null);
        } catch (IOException ioe) {
            Log.w(TAG, ioe);
            displayFrameworkBugMessageAndExit();
        } catch (RuntimeException e) {
            // Barcode Scanner has seen crashes in the wild of this variety:
            // java.?lang.?RuntimeException: Fail to connect to camera service
            Log.w(TAG, "Unexpected error initializing camera", e);
            displayFrameworkBugMessageAndExit();
        }
    }

    private void displayFrameworkBugMessageAndExit() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.app_name));
        builder.setMessage(getString(R.string.msg_camera_framework_bug));
        builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
        builder.setOnCancelListener(new FinishListener(this));
        builder.show();
    }

    public void restartPreviewAfterDelay(long delayMS) {
        if (handler != null) {
            handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
        }
        resetStatusView();
    }

    /**
     * 重置UI界面的状态
     */
    private void resetStatusView() {
        resultView.setVisibility(View.GONE);  // 隐藏
        statusView.setText(R.string.msg_default_status);
        statusView.setVisibility(View.VISIBLE);
        viewfinderView.setVisibility(View.VISIBLE);  // 扫描窗口可见
        lastResult = null;
    }

    public void drawViewfinder() {
        viewfinderView.drawViewfinder();
    }
}
