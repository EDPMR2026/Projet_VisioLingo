package com.vuzix.sample.buttonfocus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.Log;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capture d'images de la camera des lunettes via CameraX, SANS apercu a l'ecran. La camera
 * reste liee au cycle de vie de l'Activity ; {@link #capture} prend une photo a la demande,
 * la reduit (cote max + JPEG) et renvoie les octets sur un thread de fond.
 */
class CameraController {

    private static final String TAG = "VisioLingo";
    private static final int MAX_SIDE = 768;     // cote max de l'image envoyee a l'API
    private static final int JPEG_QUALITY = 75;

    interface FrameCallback {
        void onFrame(byte[] jpeg);
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile ImageCapture imageCapture;

    CameraController(Context context, LifecycleOwner lifecycleOwner) {
        this.context = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
    }

    /** Lie la camera (use case ImageCapture seul, pas de Preview) au cycle de vie. */
    void start() {
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA, imageCapture);
                Log.d(TAG, "Camera prete");
            } catch (Exception e) {
                Log.e(TAG, "Echec init camera", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /** Prend une image maintenant ; le callback recoit le JPEG reduit (thread de fond). */
    void capture(final FrameCallback cb) {
        final ImageCapture ic = imageCapture;
        if (ic == null) {
            Log.d(TAG, "capture ignoree (camera pas prete)");
            return;
        }
        ic.takePicture(exec, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                try {
                    byte[] jpeg = toScaledJpeg(image);
                    if (jpeg != null) cb.onFrame(jpeg);
                } catch (Exception e) {
                    Log.e(TAG, "Conversion image echouee", e);
                } finally {
                    image.close();
                }
            }

            @Override
            public void onError(ImageCaptureException e) {
                Log.e(TAG, "takePicture erreur", e);
            }
        });
    }

    void stop() {
        exec.shutdown(); // la camera est deliee automatiquement avec le cycle de vie
    }

    private static byte[] toScaledJpeg(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] raw = new byte[buffer.remaining()];
        buffer.get(raw);
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (bmp == null) return null;

        int rotation = image.getImageInfo().getRotationDegrees();
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));

        Bitmap out = bmp;
        if (scale < 1f || rotation != 0) {
            Matrix m = new Matrix();
            if (scale < 1f) m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            out = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        if (out != bmp) out.recycle();
        bmp.recycle();
        return baos.toByteArray();
    }
}
