package com.vuzix.sample.buttonfocus

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * VisioLingo — session audio temps reel avec OpenAI Realtime via WebRTC, et envoi d'une image
 * de la camera des lunettes a chaque prise de parole (sur speech_started) pour que GPT « voie »
 * l'objet decrit. Bascule connexion/deconnexion au bouton (tap touchpad / selection).
 */
class MainActivity : ComponentActivity(), RealtimeClient.Listener {

    private lateinit var hintView: TextView
    private lateinit var flagView: TextView
    private lateinit var keywordView: TextView
    private lateinit var contentScoreView: TextView
    private lateinit var scoreView: TextView
    private lateinit var pauseOverlay: View

    private var realtimeClient: RealtimeClient? = null
    private var cameraController: CameraController? = null
    private var micGranted = false

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent> // pour les démos
    private var screenRequested = false // pour les démos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hintView = findViewById(R.id.hintView)
        flagView = findViewById(R.id.flagView)
        keywordView = findViewById(R.id.keywordView)
        contentScoreView = findViewById(R.id.contentScoreView)
        scoreView = findViewById(R.id.scoreView)
        pauseOverlay = findViewById(R.id.pauseOverlay)

        // pour les démos, on enregistre l'écran affiché
        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                startScreenRecording(result.resultCode, data)
            } else {
                Log.d(TAG, "Enregistrement écran refusé")
            }
        }

        // on demande les permissions nécessaires (caméra + micro) si pas déjà accordées
        val needed = permissionsToRequest()
        if (needed.isEmpty()) {
            onPermissionsReady()
        } else {
            requestPermissions(needed, REQ_PERMS)
        }
    }

    private fun permissionsToRequest(): Array<String> {
        val list = mutableListOf<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) list.add(Manifest.permission.RECORD_AUDIO)
        if (!granted(Manifest.permission.CAMERA)) list.add(Manifest.permission.CAMERA)
        return list.toTypedArray()
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            onPermissionsReady()
        }
    }

    private fun onPermissionsReady() {
        if (granted(Manifest.permission.CAMERA) && cameraController == null) {
            cameraController = CameraController(this, this).also { it.start() }
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            startRealtime()
        } else {
            hintView.setText(R.string.status_permission_needed)
        }
        requestScreenCapture()
    }

    // pour les démos, on demande l'autorisation d'enregistrer l'écran (une seule fois)
    private fun requestScreenCapture() {
        if (screenRequested) return
        screenRequested = true
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mgr?.let { screenCaptureLauncher.launch(it.createScreenCaptureIntent()) }
    }

    // service en arrière plan qui enregistre l'ecran en .mp4
    private fun startScreenRecording(resultCode: Int, data: Intent) {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)

        val svc = Intent(this, ScreenRecordService::class.java).apply {
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
            putExtra(ScreenRecordService.EXTRA_WIDTH, dm.widthPixels)
            putExtra(ScreenRecordService.EXTRA_HEIGHT, dm.heightPixels)
            putExtra(ScreenRecordService.EXTRA_DPI, dm.densityDpi)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    // commence la session avec l'API
    private fun startRealtime() {
        micGranted = true
        if (realtimeClient != null) return
        realtimeClient = RealtimeClient(this, this).also { it.connect() }
        setDisconnectedOverlay(false)
    }

    // gestion de la session via les boutons de la lunette (pause/reprise)
    private fun toggleConnection() {
        val client = realtimeClient
        if (client != null) {
            client.close()
            realtimeClient = null
            setDisconnectedOverlay(true)
        } else if (micGranted) {
            startRealtime()
        } else {
            hintView.setText(R.string.status_permission_needed)
            requestPermissions(permissionsToRequest(), REQ_PERMS)
        }
    }

    // overlay de pause
    private fun setDisconnectedOverlay(disconnected: Boolean) {
        pauseOverlay.visibility = if (disconnected) View.VISIBLE else View.GONE
    }

    // interception des key events lors de l'appui sur les boutons de la lunette
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "Key down: " + KeyEvent.keyCodeToString(keyCode) + " (" + keyCode + ")")
        if (isToggleKey(keyCode)) {
            toggleConnection()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isToggleKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onDestroy() {
        realtimeClient?.close()
        realtimeClient = null
        cameraController?.stop()
        cameraController = null
        stopService(Intent(this, ScreenRecordService::class.java))
        super.onDestroy()
    }

    // === implémentation de RealtimeClient.Listener =============================

    override fun onStatus(status: String) {
        Log.d(TAG, "status: $status")
    }

    override fun onUserTranscript(text: String) {}

    override fun onAssistantTranscript(text: String) {}

    override fun onError(message: String) {
        Log.w(TAG, "error: $message")
    }

    // appelée lorsque l'API détecte une voix : on capture une image et on l'envoie à l'API
    override fun onUserSpeechStarted() {
        val client = realtimeClient
        val cam = cameraController
        if (cam != null && client != null) {
            cam.capture { jpeg -> client.sendImage(jpeg) }
        }
    }

    // appelée lorsque l'API renvoie la trame texte (langue détectée, notes, mot-clé)
    override fun onAssessment(
        keyword: String, pronunciationScore: Int, contentScore: Int, flagCountryCode: String
    ) {
        hintView.visibility = View.GONE

        val flag = flagEmoji(flagCountryCode)
        if (flag.isNotEmpty()) {
            flagView.text = flag
            flagView.visibility = View.VISIBLE
        } else {
            flagView.visibility = View.GONE
        }

        if (keyword.isNotEmpty()) {
            keywordView.text = keyword
            keywordView.visibility = View.VISIBLE
        } else {
            keywordView.visibility = View.GONE
        }

        showScore(contentScoreView, "Contenu", contentScore)
        showScore(scoreView, "Prononciation", pronunciationScore)
    }

    private fun showScore(view: TextView, label: String, score: Int) {
        if (score >= 0) {
            val s = score.coerceIn(0, 10)
            view.text = "$label : $s/10"
            view.setTextColor(scoreColor(s))
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    private fun scoreColor(s: Int): Int = when {
        s <= 4 -> 0xFFFF1744.toInt()
        s <= 7 -> 0xFFFF9100.toInt()
        else -> 0xFF00E676.toInt()
    }

    // conversion code pays ('FR', 'GB', 'ES' ...) en emoji drapeau correspondant
    private fun flagEmoji(countryCode: String?): String {
        if (countryCode == null) return ""
        val cc = countryCode.trim().uppercase(Locale.US)
        if (cc.length != 2 || !cc.matches(Regex("[A-Z]{2}"))) return ""
        val base = 0x1F1E6 // 'A' regional indicator
        val first = base + (cc[0].code - 'A'.code)
        val second = base + (cc[1].code - 'A'.code)
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    companion object {
        private const val TAG = "VisioLingo"
        private const val REQ_PERMS = 1001
    }
}
