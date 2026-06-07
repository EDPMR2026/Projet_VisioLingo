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
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * VisioLingo — session audio temps reel avec OpenAI Realtime via WebRTC, et envoi d'une image
 * de la camera des lunettes a chaque prise de parole (sur speech_started) pour que GPT « voie »
 * l'objet decrit. Bascule connexion/deconnexion au bouton (tap touchpad / selection).
 */
public class MainActivity extends ComponentActivity implements RealtimeClient.Listener {

    private static final String TAG = "VisioLingo";
    private static final int REQ_PERMS = 1001;

    private TextView statusView;
    private TextView transcriptView;
    private ScrollView transcriptScroll;
    private final StringBuilder transcriptLog = new StringBuilder();

    private RealtimeClient realtimeClient;
    private CameraController cameraController;
    private boolean micGranted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);
        transcriptView = findViewById(R.id.transcriptView);
        transcriptScroll = findViewById(R.id.transcriptScroll);

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
            statusView.setText(R.string.status_permission_needed);
        }
    }

    private void startRealtime() {
        micGranted = true;
        if (realtimeClient != null) return;
        realtimeClient = new RealtimeClient(this, this);
        realtimeClient.connect();
    }

    /** Bouton des lunettes : coupe la connexion si active, sinon la (re)lance. */
    private void toggleConnection() {
        if (realtimeClient != null) {
            realtimeClient.close();
            realtimeClient = null;
            statusView.setText("Déconnecté — appuyez pour reconnecter");
            appendLine("⏸ Déconnecté");
        } else if (micGranted) {
            appendLine("▶ Reconnexion…");
            startRealtime();
        } else {
            statusView.setText(R.string.status_permission_needed);
            requestPermissions(permissionsToRequest(), REQ_PERMS);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "Key down: " + KeyEvent.keyCodeToString(keyCode) + " (" + keyCode + ")");
        if (isToggleKey(keyCode)) {
            toggleConnection();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Touches qui basculent la connexion : tap du touchpad / bouton de sélection. */
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
        super.onDestroy();
    }

    // === RealtimeClient.Listener (deja sur le thread principal) =============================

    @Override
    public void onStatus(String status) {
        statusView.setText(status);
    }

    @Override
    public void onUserTranscript(String text) {
        appendLine("🧑 " + text);
    }

    @Override
    public void onAssistantTranscript(String text) {
        appendLine("🤖 " + text);
    }

    @Override
    public void onError(String message) {
        appendLine("⚠ " + message);
    }

    /** L'utilisateur commence a parler -> on capture une image et on l'envoie au modele. */
    @Override
    public void onUserSpeechStarted() {
        final RealtimeClient client = realtimeClient;
        if (cameraController != null && client != null) {
            cameraController.capture(jpeg -> client.sendImage(jpeg));
        }
    }

    private void appendLine(String line) {
        if (transcriptLog.length() > 0) {
            transcriptLog.append("\n\n");
        }
        transcriptLog.append(line);
        transcriptView.setText(transcriptLog.toString());
        transcriptScroll.post(() -> transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
