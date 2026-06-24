/*
Copyright (c) 2019, Vuzix Corporation
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

*  Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

*  Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

*  Neither the name of Vuzix Corporation nor the names of
   its contributors may be used to endorse or promote products derived
   from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.vuzix.sample.buttonfocus;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


// Activité principale (et unique)
// Orchestre le fonctionnement global de l'application
// Utilise RealtimeClient pour la communication avec l'API
// Utilise CameraController pour prendre les photos
// (optionnel) enregistre l'écran pour les démos
public class MainActivity extends ComponentActivity implements RealtimeClient.Listener {

    private static final String TAG = "VisioLingo";
    private static final int REQ_PERMS = 1001;

    private TextView hintView;
    private TextView flagView;
    private TextView keywordView;
    private TextView contentScoreView;
    private TextView scoreView;
    private View pauseOverlay;

    private RealtimeClient realtimeClient;
    private CameraController cameraController;
    private boolean micGranted = false;

    private ActivityResultLauncher<Intent> screenCaptureLauncher; // pour les démos
    private boolean screenRequested = false; // pour les démos

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hintView = findViewById(R.id.hintView);
        flagView = findViewById(R.id.flagView);
        keywordView = findViewById(R.id.keywordView);
        contentScoreView = findViewById(R.id.contentScoreView);
        scoreView = findViewById(R.id.scoreView);
        pauseOverlay = findViewById(R.id.pauseOverlay);

        // pour les démos, on enregistre l'écran affiché
        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        startScreenRecording(result.getResultCode(), result.getData());
                    } else {
                        Log.d(TAG, "Enregistrement écran refusé");
                    }
                });

        // on demande les permissions nécessaires (si pas déjà accordées) pour accéder à la caméra et capturer le son:
        String[] needed = permissionsToRequest();
        if (needed.length == 0) {
            onPermissionsReady();
        } else {
            requestPermissions(needed, REQ_PERMS);
        }
    }

    private String[] permissionsToRequest() {
        List<String> list = new ArrayList<>();
        if (!granted(Manifest.permission.RECORD_AUDIO)) list.add(Manifest.permission.RECORD_AUDIO);
        if (!granted(Manifest.permission.CAMERA)) list.add(Manifest.permission.CAMERA);
        return list.toArray(new String[0]);
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            onPermissionsReady();
        }
    }

    private void onPermissionsReady() {
        if (granted(Manifest.permission.CAMERA) && cameraController == null) {
            cameraController = new CameraController(this, this);
            cameraController.start();
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            startRealtime();
        } else {
            hintView.setText(R.string.status_permission_needed);
        }
        requestScreenCapture();
    }

    // pour les démos, on demande l'autorisation d'enregistrer l'écran
    private void requestScreenCapture() {
        if (screenRequested) return;
        screenRequested = true;
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr != null) {
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent());
        }
    }

    // service en arrière plan qui enregistre l'ecran en .mp4
    private void startScreenRecording(int resultCode, Intent data) {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);

        Intent svc = new Intent(this, ScreenRecordService.class);
        svc.putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(ScreenRecordService.EXTRA_DATA, data);
        svc.putExtra(ScreenRecordService.EXTRA_WIDTH, dm.widthPixels);
        svc.putExtra(ScreenRecordService.EXTRA_HEIGHT, dm.heightPixels);
        svc.putExtra(ScreenRecordService.EXTRA_DPI, dm.densityDpi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    // commence la session avec l'API
    private void startRealtime() {
        micGranted = true;
        if (realtimeClient != null) return;
        realtimeClient = new RealtimeClient(this, this);
        realtimeClient.connect();
        setDisconnectedOverlay(false);
    }

    // gestion de la session via les boutons de la lunette (pause/reprise)
    private void toggleConnection() {
        if (realtimeClient != null) {
            realtimeClient.close();
            realtimeClient = null;
            setDisconnectedOverlay(true);
        } else if (micGranted) {
            startRealtime();
        } else {
            hintView.setText(R.string.status_permission_needed);
            requestPermissions(permissionsToRequest(), REQ_PERMS);
        }
    }

    // overlay de pause
    private void setDisconnectedOverlay(boolean disconnected) {
        if (pauseOverlay != null) {
            pauseOverlay.setVisibility(disconnected ? View.VISIBLE : View.GONE);
        }
    }

    // interception des key events lors de l'appuie sur les boutons
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "Key down: " + KeyEvent.keyCodeToString(keyCode) + " (" + keyCode + ")");
        if (isToggleKey(keyCode)) {
            toggleConnection();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isToggleKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    protected void onDestroy() {
        if (realtimeClient != null) {
            realtimeClient.close();
            realtimeClient = null;
        }
        if (cameraController != null) {
            cameraController.stop();
            cameraController = null;
        }
        stopService(new Intent(this, ScreenRecordService.class));
        super.onDestroy();
    }

    // === implémentation de RealtimeClient.Listener =============================

    @Override
    public void onStatus(String status) {
        Log.d(TAG, "status: " + status);
    }

    @Override
    public void onUserTranscript(String text) {}

    @Override
    public void onAssistantTranscript(String text) {}

    @Override
    public void onError(String message) {
        Log.w(TAG, "error: " + message);
    }

    // fonction appelée lorsque l'API détecte une voix
    // on capture une image et on l'envoie à l'API
    @Override
    public void onUserSpeechStarted() {
        final RealtimeClient client = realtimeClient;
        if (cameraController != null && client != null) {
            cameraController.capture(jpeg -> client.sendImage(jpeg));
        }
    }

    // fonction appelée lorsque l'API renvoit la trame texte (qui contient la langue détectée, et le feedback textuel)
    @Override
    public void onAssessment(String keyword, int pronunciationScore, int contentScore, String flagCountryCode) {
        hintView.setVisibility(View.GONE);

        String flag = flagEmoji(flagCountryCode);
        if (!flag.isEmpty()) {
            flagView.setText(flag);
            flagView.setVisibility(View.VISIBLE);
        } else {
            flagView.setVisibility(View.GONE);
        }

        if (keyword != null && !keyword.isEmpty()) {
            keywordView.setText(keyword);
            keywordView.setVisibility(View.VISIBLE);
        } else {
            keywordView.setVisibility(View.GONE);
        }

        showScore(contentScoreView, "Contenu", contentScore);
        showScore(scoreView, "Prononciation", pronunciationScore);
    }

    private void showScore(TextView view, String label, int score) {
        if (score >= 0) {
            int s = Math.max(0, Math.min(10, score));
            view.setText(label + " : " + s + "/10");
            view.setTextColor(scoreColor(s));
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    // utils

    private int scoreColor(int s) {
        if (s <= 4) return 0xFFFF1744;
        if (s <= 7) return 0xFFFF9100;
        return 0xFF00E676;
    }

    // conversion code langue ('fr', 'en', 'es' ...) en emoji drapeau correspondant
    private String flagEmoji(String countryCode) {
        if (countryCode == null) return "";
        String cc = countryCode.trim().toUpperCase(Locale.US);
        if (cc.length() != 2 || !cc.matches("[A-Z]{2}")) return "";
        int base = 0x1F1E6; // 'A' regional indicator
        int first = base + (cc.charAt(0) - 'A');
        int second = base + (cc.charAt(1) - 'A');
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }
}
