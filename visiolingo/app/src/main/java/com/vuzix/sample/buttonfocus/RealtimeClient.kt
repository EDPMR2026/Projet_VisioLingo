package com.vuzix.sample.buttonfocus

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Client WebRTC vers l'API OpenAI Realtime (modele audio natif gpt-realtime-2).
 * Flux :
 *   1. Construit une PeerConnection avec une piste audio locale (micro) et un data channel
 *      "oai-events".
 *   2. Cree une offre SDP, attend la fin du gathering ICE, puis POST l'offre en multipart vers
 *      /v1/realtime/calls (parties "sdp" + "session"). La reponse est la SDP answer.
 *   3. A l'ouverture du data channel, envoie un session.update (instructions VisioLingo, VAD,
 *      transcription) et ecoute les events (transcripts / erreurs).
 *
 * L'audio de reponse de GPT est joue automatiquement par le module audio WebRTC sur les
 * haut-parleurs des lunettes ; le micro est capture automatiquement.
 */
class RealtimeClient(context: Context, private val listener: Listener) {

    // === Callback vers l'UI (implemente par MainActivity) ===================================
    interface Listener {
        fun onStatus(status: String)
        fun onUserTranscript(text: String)
        fun onAssistantTranscript(text: String)
        fun onError(message: String)
        fun onUserSpeechStarted()
        fun onAssessment(keyword: String, pronunciationScore: Int, contentScore: Int,
                         flagCountryCode: String)
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient()
    private val offerSent = AtomicBoolean(false)
    private val assistantBuffer = StringBuilder()

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var audioManager: AudioManager? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteSink: AudioTrackSink? = null
    private var wavRecorder: WavRecorder? = null
    private val recorderExec: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var statsStarted = false
    @Volatile private var pendingReport = false

    /** Logue toutes les 2 s le niveau du micro local et les octets audio envoyes a OpenAI. */
    private val statsLogger: Runnable = object : Runnable {
        override fun run() {
            val pc = peerConnection
            if (pc == null || closed) return
            pc.getStats { report ->
                for (s in report.statsMap.values) {
                    if ("media-source" == s.type && "audio" == s.members["kind"].toString()) {
                        Log.d(TAG, "MIC level=" + s.members["audioLevel"]
                                + " energy=" + s.members["totalAudioEnergy"])
                    } else if ("outbound-rtp" == s.type && "audio" == s.members["kind"].toString()) {
                        Log.d(TAG, "OUT bytesSent=" + s.members["bytesSent"]
                                + " packetsSent=" + s.members["packetsSent"])
                    }
                }
            }
            mainHandler.postDelayed(this, 2000L)
        }
    }

    // === Cycle de vie =======================================================================

    fun connect() {
        notifyStatus("Initialisation WebRTC…")
        configureAudioForCall()
        initFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val pc = factory!!.createPeerConnection(config, pcObserver)
        if (pc == null) {
            notifyError("Echec de creation de la PeerConnection")
            return
        }
        peerConnection = pc

        // Piste audio locale (micro). En Unified Plan, addTrack cree un transceiver sendrecv,
        // donc on recevra aussi l'audio de GPT.
        val source = factory!!.createAudioSource(MediaConstraints())
        audioSource = source
        val track = factory!!.createAudioTrack("audio0", source)
        localAudioTrack = track
        pc.addTrack(track, listOf("stream0"))

        // Data channel pour les events JSON OpenAI.
        val dc = pc.createDataChannel(DATA_CHANNEL_LABEL, DataChannel.Init())
        dataChannel = dc
        dc.registerObserver(dcObserver)

        // Cree l'offre puis fixe la description locale ; le POST partira a la fin du gathering ICE.
        pc.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                pc.setLocalDescription(SimpleSdpObserver("setLocal"), sdp)
            }
        }, MediaConstraints())

        // Filet de securite : si onIceGatheringChange(COMPLETE) tarde, on poste quand meme.
        mainHandler.postDelayed({ maybeSendOffer() }, ICE_GATHERING_TIMEOUT_MS)
    }

    fun close() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        dataChannel?.let {
            try { it.unregisterObserver() } catch (ignored: Exception) {}
            it.dispose()
        }
        dataChannel = null
        peerConnection?.let {
            it.close()
            it.dispose()
        }
        peerConnection = null
        val rt = remoteAudioTrack
        val rs = remoteSink
        if (rt != null && rs != null) {
            try { rt.removeSink(rs) } catch (ignored: Exception) {}
        }
        remoteSink = null
        remoteAudioTrack = null
        val rec = wavRecorder
        wavRecorder = null
        rec?.let { r -> recorderExec.execute { r.stop() } } // s'execute apres les append() en file
        recorderExec.shutdown()
        localAudioTrack?.dispose(); localAudioTrack = null
        audioSource?.dispose(); audioSource = null
        factory?.dispose(); factory = null
        audioDeviceModule?.release(); audioDeviceModule = null
        restoreAudioMode()
        httpExecutor.shutdownNow()
    }

    // === WebRTC factory =====================================================================

    /**
     * Passe l'appareil en mode communication (type VoIP) pour obtenir la priorite micro face a
     * l'assistant vocal always-on des lunettes Vuzix, et route la sortie sur le haut-parleur.
     */
    private fun configureAudioForCall() {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager = am
            if (am != null) {
                previousAudioMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                Log.d(TAG, "Audio mode -> IN_COMMUNICATION (was $previousAudioMode)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "configureAudioForCall failed", e)
        }
    }

    private fun restoreAudioMode() {
        try {
            audioManager?.let {
                it.mode = previousAudioMode
                it.isSpeakerphoneOn = false
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Demarre l'enregistrement de la voix de GPT (piste audio distante) dans un fichier WAV,
     * pour reutilisation au montage video. Fichier dans le dossier externe de l'appli :
     * /sdcard/Android/data/<pkg>/files/visiolingo_gpt_<horodatage>.wav (recuperable via adb pull).
     */
    private fun startRecording(remoteTrack: AudioTrack) {
        if (wavRecorder != null) return // une seule session d'enregistrement
        val dir = appContext.getExternalFilesDir(null)
        val name = "visiolingo_gpt_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        val rec = WavRecorder(File(dir, name))
        wavRecorder = rec
        remoteAudioTrack = remoteTrack
        val sink = AudioTrackSink {
            audioData, bitsPerSample, sampleRate, numberOfChannels, _, _ ->
            val len = audioData.remaining()
            if (len > 0) {
                val buf = ByteArray(len)
                audioData.duplicate().get(buf)
                recorderExec.execute { rec.append(buf, sampleRate, numberOfChannels, bitsPerSample) }
            }
        }
        remoteSink = sink
        remoteTrack.addSink(sink)
        Log.d(TAG, "Enregistrement voix GPT -> " + rec.file.absolutePath)
        mainHandler.postDelayed(wavFlusher, 3000L)
    }

    /** Reecrit periodiquement l'en-tete WAV pour que le fichier reste lisible meme sans fermer. */
    private val wavFlusher: Runnable = object : Runnable {
        override fun run() {
            val rec = wavRecorder
            if (rec == null || closed) return
            try { recorderExec.execute { rec.flush() } } catch (ignored: Exception) {}
            mainHandler.postDelayed(this, 3000L)
        }
    }

    private fun initFactory() {
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            // Micro brut + AEC/NS materiel desactives : sur le M4000 en MODE_NORMAL, le
            // traitement VOICE_COMMUNICATION + AEC HW semblait annuler l'entree micro.
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() { Log.d(TAG, "MIC: recording started") }
                override fun onWebRtcAudioRecordStop() { Log.d(TAG, "MIC: recording stopped") }
            })
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    notifyError("MIC init error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?
                ) {
                    notifyError("MIC start error ($errorCode): $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    notifyError("MIC error: $errorMessage")
                }
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() { Log.d(TAG, "SPEAKER: playout started") }
                override fun onWebRtcAudioTrackStop() { Log.d(TAG, "SPEAKER: playout stopped") }
            })
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    // === Signaling SDP ======================================================================

    /** Poste l'offre locale (avec candidats ICE) vers OpenAI, une seule fois. */
    private fun maybeSendOffer() {
        if (closed || !offerSent.compareAndSet(false, true)) return
        val pc = peerConnection
        val local = pc?.localDescription
        if (pc == null || local == null) {
            notifyError("Offre SDP indisponible")
            return
        }
        val offerSdp = local.description
        notifyStatus("Connexion a OpenAI…")

        httpExecutor.execute {
            try {
                val session = JSONObject()
                session.put("type", "realtime")
                session.put("model", MODEL)
                session.put("audio", JSONObject().put("output", JSONObject().put("voice", VOICE)))

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("sdp", offerSdp)
                    .addFormDataPart("session", session.toString())
                    .build()

                val request = Request.Builder()
                    .url(CALLS_URL)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .post(body)
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        notifyError("POST SDP echoue : " + e.message)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { r ->
                            try {
                                val bodyStr = r.body?.string() ?: ""
                                if (!r.isSuccessful) {
                                    notifyError("OpenAI HTTP " + r.code + " : " + bodyStr)
                                    return
                                }
                                val answer = SessionDescription(
                                    SessionDescription.Type.ANSWER, bodyStr
                                )
                                peerConnection?.setRemoteDescription(
                                    SimpleSdpObserver("setRemote"), answer
                                )
                            } catch (e: Exception) {
                                notifyError("Reponse SDP invalide : " + e.message)
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                notifyError("Erreur signaling : " + e.message)
            }
        }
    }

    // === Events OpenAI (data channel) =======================================================

    /** Envoie la configuration de session des l'ouverture du data channel. */
    private fun sendSessionUpdate() {
        try {
            val turnDetection = JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.5)
                .put("prefix_padding_ms", 300)
                .put("silence_duration_ms", 500)
                .put("create_response", true)
            val input = JSONObject()
                .put("turn_detection", turnDetection)
                .put("transcription", JSONObject().put("model", "whisper-1"))
            val audio = JSONObject()
                .put("input", input)
                .put("output", JSONObject().put("voice", VOICE))
            val sessionObj = JSONObject()
                .put("type", "realtime")
                .put("instructions", INSTRUCTIONS)
                .put("output_modalities", JSONArray().put("audio"))
                .put("audio", audio)
                .put("tools", buildTools())
                .put("tool_choice", "none")
            val event = JSONObject()
                .put("type", "session.update")
                .put("session", sessionObj)
            sendEvent(event)
            Log.d(TAG, "session.update sent (server_vad)")
        } catch (e: Exception) {
            notifyError("session.update invalide : " + e.message)
        }
    }

    // fonction report_assessment, la meme que promptee au modele
    private fun buildTools(): JSONArray {
        val props = JSONObject()
            .put("keyword", JSONObject()
                .put("type", "string")
                .put("description", "Mot principal designant l'objet, dans la langue parlee."))
            .put("pronunciation_score", JSONObject()
                .put("type", "integer")
                .put("minimum", 0)
                .put("maximum", 10)
                .put("description", "Note de prononciation/accent de 0 a 10."))
            .put("content_score", JSONObject()
                .put("type", "integer")
                .put("minimum", 0)
                .put("maximum", 10)
                .put("description", "Note de justesse/pertinence de la description de 0 a 10."))
            .put("flag_country_code", JSONObject()
                .put("type", "string")
                .put("description", "Code pays ISO 3166-1 alpha-2 (majuscules) de la langue parlee."))
        val params = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray()
                .put("keyword").put("pronunciation_score")
                .put("content_score").put("flag_country_code"))
        val tool = JSONObject()
            .put("type", "function")
            .put("name", "report_assessment")
            .put("description", "Renvoie une evaluation structuree de la description de l'utilisateur.")
            .put("parameters", params)
        return JSONArray().put(tool)
    }

    /** Demande une reponse dediee qui FORCE l'appel de report_assessment (sans parole). */
    private fun requestAssessment() {
        try {
            val toolChoice = JSONObject()
                .put("type", "function")
                .put("name", "report_assessment")
            val response = JSONObject()
                .put("output_modalities", JSONArray().put("text"))
                .put("tool_choice", toolChoice)
            val event = JSONObject()
                .put("type", "response.create")
                .put("response", response)
            sendEvent(event)
            Log.d(TAG, "response.create (report_assessment force)")
        } catch (e: Exception) {
            notifyError("requestAssessment: " + e.message)
        }
    }

    /** Acquitte un appel de fonction pour garder l'etat de conversation propre. */
    private fun sendFunctionCallOutput(callId: String?) {
        if (callId.isNullOrEmpty()) return
        try {
            val item = JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", "ok")
            val event = JSONObject()
                .put("type", "conversation.item.create")
                .put("item", item)
            sendEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "function_call_output", e)
        }
    }

    private fun sendEvent(event: JSONObject) {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) return
        val bytes = event.toString().toByteArray(StandardCharsets.UTF_8)
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    /**
     * Ajoute une image JPEG au contexte de la conversation (event conversation.item.create avec
     * un contenu input_image en data URL base64).
     */
    fun sendImage(jpeg: ByteArray?) {
        if (jpeg == null || jpeg.isEmpty()) return
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            Log.d(TAG, "image ignoree (data channel non ouvert)")
            return
        }
        try {
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val content = JSONObject()
                .put("type", "input_image")
                .put("image_url", dataUrl)
            val item = JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", JSONArray().put(content))
            val event = JSONObject()
                .put("type", "conversation.item.create")
                .put("item", item)
            sendEvent(event)
            Log.d(TAG, "image envoyee (" + jpeg.size + " octets)")
        } catch (e: Exception) {
            notifyError("sendImage: " + e.message)
        }
    }

    private fun handleEvent(json: String) {
        try {
            val event = JSONObject(json)
            val type = event.optString("type", "")
            Log.d(TAG, "evt <= $type")
            when (type) {
                "session.created" -> notifyStatus("Connecte — a l'ecoute")
                "session.updated" -> Log.d(TAG, "session.updated")
                "input_audio_buffer.speech_started" -> {
                    notifyStatus("🎤 …")
                    mainHandler.post { listener.onUserSpeechStarted() }
                }
                "input_audio_buffer.speech_stopped" -> {
                    notifyStatus("Connecte — a l'ecoute")
                    pendingReport = true // ce tour devra produire une evaluation
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val t = event.optString("transcript", "").trim()
                    if (t.isNotEmpty()) notifyUser(t)
                }
                "response.output_audio_transcript.delta" ->
                    assistantBuffer.append(event.optString("delta", ""))
                "response.output_audio_transcript.done" -> {
                    val full = assistantBuffer.toString().trim()
                    assistantBuffer.setLength(0)
                    if (full.isNotEmpty()) notifyAssistant(full)
                }
                "response.done" -> {
                    val full = assistantBuffer.toString().trim()
                    assistantBuffer.setLength(0)
                    if (full.isNotEmpty()) notifyAssistant(full)
                    // La reponse audio du tour est finie -> on demande l'evaluation structuree.
                    if (pendingReport) {
                        pendingReport = false
                        requestAssessment()
                    }
                }
                "response.function_call_arguments.done" -> handleFunctionCall(event)
                "error" -> {
                    Log.e(TAG, "error event: $json")
                    val err = event.optJSONObject("error")
                    notifyError(if (err != null) err.optString("message", json) else json)
                }
                else -> Log.d(TAG, "event: $type")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Event illisible : $json", e)
        }
    }

    /** Parse les arguments JSON de report_assessment et remonte l'evaluation a l'UI. */
    private fun handleFunctionCall(event: JSONObject) {
        val args = event.optString("arguments", "")
        if (args.isEmpty()) return
        try {
            val a = JSONObject(args)
            val keyword = a.optString("keyword", "").trim()
            val score = a.optInt("pronunciation_score", -1)
            val contentScore = a.optInt("content_score", -1)
            val flag = a.optString("flag_country_code", "").trim().uppercase(Locale.US)
            notifyAssessment(keyword, score, contentScore, flag)
            sendFunctionCallOutput(event.optString("call_id", ""))
        } catch (e: Exception) {
            Log.w(TAG, "report_assessment illisible : $args", e)
        }
    }

    // === Observers ==========================================================================

    private val pcObserver: PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                maybeSendOffer()
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection: $state")
            if (state == PeerConnection.IceConnectionState.FAILED) {
                notifyError("Connexion ICE echouee")
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            Log.d(TAG, "PC state: $state")
            if (DEBUG_STATS && state == PeerConnection.PeerConnectionState.CONNECTED
                && !statsStarted) {
                statsStarted = true
                mainHandler.post(statsLogger)
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track: MediaStreamTrack? = receiver?.track()
            if (track is AudioTrack) {
                track.setEnabled(true)
                Log.d(TAG, "Piste audio distante recue")
                startRecording(track)
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private val dcObserver: DataChannel.Observer = object : DataChannel.Observer {
        override fun onStateChange() {
            val dc = dataChannel ?: return
            val state = dc.state()
            Log.d(TAG, "DataChannel: $state")
            if (state == DataChannel.State.OPEN) {
                sendSessionUpdate()
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val data = buffer.data
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            handleEvent(String(bytes, StandardCharsets.UTF_8))
        }

        override fun onBufferedAmountChange(previousAmount: Long) {}
    }

    /** SdpObserver minimal qui logue les echecs. */
    private open inner class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { notifyError("$tag : $error") }
        override fun onSetFailure(error: String?) { notifyError("$tag : $error") }
    }

    // === Notifications UI (thread principal) ================================================

    private fun notifyStatus(s: String) { mainHandler.post { listener.onStatus(s) } }
    private fun notifyUser(s: String) { mainHandler.post { listener.onUserTranscript(s) } }
    private fun notifyAssistant(s: String) { mainHandler.post { listener.onAssistantTranscript(s) } }
    private fun notifyAssessment(keyword: String, score: Int, contentScore: Int, flag: String) {
        mainHandler.post { listener.onAssessment(keyword, score, contentScore, flag) }
    }
    private fun notifyError(s: String) {
        Log.e(TAG, s)
        mainHandler.post { listener.onError(s) }
    }

    companion object {
        private const val TAG = "VisioLingo"

        // === Configuration ==================================================================
        // Cle chargee depuis local.properties (fichier en git ignore) via BuildConfig
        private val API_KEY: String = BuildConfig.OPENAI_API_KEY

        private const val CALLS_URL = "https://api.openai.com/v1/realtime/calls"
        private const val MODEL = "gpt-realtime-2"
        private const val VOICE = "marin"
        private const val DATA_CHANNEL_LABEL = "oai-events"
        private const val ICE_GATHERING_TIMEOUT_MS = 3000L
        // Passe a true pour loguer le niveau micro / octets envoyes toutes les 2 s (debug hardware).
        private const val DEBUG_STATS = false

        private var factoryInitialized = false

        private const val INSTRUCTIONS =
            "Tu es VisioLingo, un coach linguistique vocal. L'utilisateur porte des lunettes de realite augmentee " +
            "qui ont une camera et un micro : il regarde un objet et le decrit a voix haute dans la langue de son choix. " +
            "Juste avant chaque prise de parole, tu recois une image de ce qu'il regarde. " +
            "Sers-toi de cette image pour juger si sa description correspond bien a l'objet, " +
            "evaluer sa prononciation et son accent, corriger les erreurs et proposer une " +
            "formulation plus naturelle si c'est nécessaire. Si l'objet ne correspond pas a " +
            "ce qu'il dit, dis-lui le bon mot. " +
            "REGLE DE LANGUE STRICTE : detecte la langue parlee par l'utilisateur " +
            "dans son audio, et reponds EXCLUSIVEMENT dans cette langue la, quoi qu'il arrive. " +
            "N'utilise jamais une autre langue par defaut : si l'utilisateur parle anglais, " +
            "reponds en anglais ; s'il parle espagnol, reponds en espagnol ; etc. " +
            "SOIS EXIGEANT ET STRICT dans ton evaluation, sans complaisance : juge finement la " +
            "prononciation et l'accent (voyelles, intonation, accent tonique, liaisons, sons " +
            "specifiques a la langue) et signale clairement chaque defaut a l'oral. " +
            " si toutefois tu sens que l'utilisateur parle très bien la langue, pas besoin de le corriger pour rien! " +
            "Reste bref et constructif (1 a 3 phrases), mais ne survends pas : ne felicite que " +
            "ce qui est reellement bon. Si l'image est floue ou si tu n'as " +
            "pas compris, demande de repeter. " +
            "Lorsque l'on te demande d'appeler la fonction report_assessment, renseigne " +
            ": keyword (le mot principal designant l'objet, dans la langue parlee par " +
            "l'utilisateur), pronunciation_score (entier de 0 a 10 evaluant la prononciation et " +
            "l'accent), content_score (entier de 0 a 10 evaluant la justesse et la pertinence de " +
            "la description par rapport a l'objet reellement vu sur l'image). " +
            "NOTE AVEC EXIGENCE, selon le bareme : 9-10 = niveau quasi natif/parfait, " +
            "7-8 = bon mais defauts perceptibles, 5-6 = comprehensible avec erreurs nettes, " +
            "3-4 = difficile a comprendre, 0-2 = incorrect. Reserve 9-10 a l'excellence reelle " +
            "et n'hesite pas a mettre des notes basses quand c'est justifie. Enfin flag_country_code " +
            "(code pays ISO 3166-1 alpha-2 en majuscules le plus representatif de la langue " +
            "parlee, ex : FR, GB, ES, DE, IT, JP)."
    }
}
