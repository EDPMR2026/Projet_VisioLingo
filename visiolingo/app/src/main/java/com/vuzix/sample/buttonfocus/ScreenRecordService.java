package com.vuzix.sample.buttonfocus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Enregistre l'ecran de la lunette dans un .mp4 (a cote des .wav) via MediaProjection +
 * MediaRecorder, sans PC branche. Demarre comme service foreground (impose des qu'on capture
 * l'ecran). Le consentement systeme est obtenu par {@link MainActivity} qui passe le resultCode
 * et l'Intent de projection en extras.
 */
public class ScreenRecordService extends Service {

    private static final String TAG = "VisioLingo";
    private static final String CHANNEL_ID = "visiolingo_screenrec";
    private static final int NOTIF_ID = 42;

    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_WIDTH = "width";
    static final String EXTRA_HEIGHT = "height";
    static final String EXTRA_DPI = "dpi";

    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;
    private boolean recording = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || recording) return START_NOT_STICKY;

        startForegroundNotif();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        int width = intent.getIntExtra(EXTRA_WIDTH, 854);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 480);
        int dpi = intent.getIntExtra(EXTRA_DPI, 212);
        if (data == null) {
            Log.e(TAG, "ScreenRecord: pas de donnees de projection");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startRecording(resultCode, data, width, height, dpi);
        } catch (Exception e) {
            Log.e(TAG, "ScreenRecord: echec demarrage", e);
            cleanup();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startRecording(int resultCode, Intent data, int width, int height, int dpi)
            throws Exception {
        // Aligne les dimensions sur des multiples de 16 (exige par certains encodeurs H264).
        int w = (width / 16) * 16;
        int h = (height / 16) * 16;

        File dir = getExternalFilesDir(null);
        String name = "visiolingo_screen_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        File out = new File(dir, name);

        recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(w, h);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(4_000_000);
        recorder.setOutputFile(out.getAbsolutePath());
        recorder.prepare();

        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mgr.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection.onStop");
                stopRecording();
            }
        }, null);

        virtualDisplay = projection.createVirtualDisplay(
                "VisioLingoScreen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, null);

        recorder.start();
        recording = true;
        Log.d(TAG, "ScreenRecord start: " + out.getAbsolutePath() + " (" + w + "x" + h + ")");
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    private void cleanup() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception e) {
                Log.w(TAG, "recorder.stop", e);
            }
            try {
                recorder.reset();
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        Log.d(TAG, "ScreenRecord done");
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Enregistrement écran", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VisioLingo")
                .setContentText("Enregistrement de l'écran en cours")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }
}
