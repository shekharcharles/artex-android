package com.artex.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫码页：相机预览 + zxing 实时解码，识别到内容即回传。
 *
 * 几个关键点：
 *  - 相机权限是异步申请的，首次进入时 surfaceCreated 会早于授权返回，
 *    因此授权回调里必须补一次 openCamera，否则相机永远打不开。
 *  - 预览帧不做旋转：zxing 的定位图案检测本就不依赖方向，
 *    多一次旋转反而容易出错（与官方 Android 示例保持一致）。
 *  - 解码放到单独线程：整幅预览图上跑一次识别要几十到几百毫秒，
 *    留在相机回调线程上会把后续帧全堵住。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    static final String EXTRA_RESULT = "scan_result";
    private static final int REQ_CAMERA = 2001;
    private static final int MAX_PREVIEW_W = 1280;
    private static final int MAX_PREVIEW_H = 720;

    private SurfaceView surface;
    private Camera camera;
    private final MultiFormatReader reader = new MultiFormatReader();
    private ExecutorService decodePool;
    private volatile boolean decoding;
    private volatile boolean found;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        // 只认二维码：混入条码格式会让每帧在整幅图上先跑一遍条码扫描，把二维码识别拖垮
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("Align the QR code within the frame");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 24, 0, 24);
        hint.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;
        root.addView(hint, hp);

        setContentView(root);
        surface.getHolder().addCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23 && !hasCameraPermission()) {
            requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_CAMERA);
        } else if (camera == null && surface.getHolder().getSurface() != null
                && surface.getHolder().getSurface().isValid()) {
            openCamera(surface.getHolder());
        }
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (decodePool != null) {
            decodePool.shutdownNow();
            decodePool = null;
        }
        super.onDestroy();
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_CAMERA) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // surfaceCreated 已经跑过且当时没权限 → 这里补开相机
        SurfaceHolder holder = surface.getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            openCamera(holder);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (hasCameraPermission()) openCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (camera != null) {
            try {
                camera.startPreview();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private Camera.Size pickPreviewSize(Camera.Parameters p) {
        Camera.Size best = null;
        for (Camera.Size s : p.getSupportedPreviewSizes()) {
            if (s.width <= MAX_PREVIEW_W && s.height <= MAX_PREVIEW_H) {
                if (best == null || (long) s.width * s.height > (long) best.width * best.height) best = s;
            }
        }
        if (best == null && !p.getSupportedPreviewSizes().isEmpty()) {
            best = p.getSupportedPreviewSizes().get(0);
        }
        return best;
    }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null) return;   // 授权回调与 surfaceCreated 都可能触发，防重入
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();

            Camera.Size size = pickPreviewSize(p);
            if (size != null) p.setPreviewSize(size.width, size.height);

            // 不连续对焦的话画面一直是虚的，二维码识别不出来
            List<String> focus = p.getSupportedFocusModes();
            if (focus != null) {
                if (focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            p.setRecordingHint(true);
            camera.setParameters(p);

            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();

            if (decodePool == null || decodePool.isShutdown()) {
                decodePool = Executors.newSingleThreadExecutor();
            }
        } catch (Throwable t) {
            releaseCamera();
            Toast.makeText(this, "Unable to open camera: " + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.cancelAutoFocus();
                camera.stopPreview();
            } catch (Throwable ignored) {}
            try {
                camera.release();
            } catch (Throwable ignored) {}
            camera = null;
        }
    }

    /** 点按画面手动对焦（连续对焦在某些场景不灵） */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && camera != null) {
            try {
                camera.autoFocus(null);
            } catch (Throwable ignored) {}
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (decoding || found || data == null) return;
        final ExecutorService pool = decodePool;
        if (pool == null) return;

        Camera.Size size;
        try {
            size = cam.getParameters().getPreviewSize();
        } catch (Throwable t) {
            return;
        }
        final int w = size.width;
        final int h = size.height;
        if (data.length < w * h) return;

        // 相机 buffer 会被复用，先拷一份交给解码线程
        final byte[] frame = new byte[w * h];
        System.arraycopy(data, 0, frame, 0, w * h);
        decoding = true;

        pool.execute(() -> {
            Result r = null;
            try {
                // 不旋转：直接按传感器原始方向交给 zxing
                PlanarYUVLuminanceSource src =
                        new PlanarYUVLuminanceSource(frame, w, h, 0, 0, w, h, false);
                BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
                r = reader.decodeWithState(bmp);
            } catch (Throwable ignored) {
                // NotFoundException 是常态：连续帧里总会有能识别的
            } finally {
                reader.reset();
                decoding = false;
            }
            if (r != null) {
                found = true;
                final String text = r.getText();
                runOnUiThread(() -> {
                    Intent out = new Intent();
                    out.putExtra(EXTRA_RESULT, text);
                    setResult(RESULT_OK, out);
                    finish();
                });
            }
        });
    }
}
