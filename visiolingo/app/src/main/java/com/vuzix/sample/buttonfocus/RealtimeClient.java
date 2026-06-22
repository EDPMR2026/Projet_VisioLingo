package com.vuzix.sample.buttonfocus;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.AudioTrackSink;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// Client WebRTC vers l'API OpenAI Realtime (modele audio natif gpt-realtime-2).
//Flux :
//   1. Construit une PeerConnection avec une piste audio locale (micro) et un data channel
//      "oai-events".
//   2. Cree une offre SDP, attend la fin du gathering ICE, puis POST l'offre en multipart vers
//      /v1/realtime/calls (parties "sdp" + "session"). La reponse est la SDP answer.
//   3. A l'ouverture du data channel, envoie un session.update (instructions VisioLingo, VAD,
//      transcription) et ecoute les events (transcripts / erreurs).
//
// L'audio de reponse de GPT est joue automatiquement par le module audio WebRTC sur les
// haut-parleurs des lunettes ; le micro est capture automatiquement.

public class RealtimeClient {

    private static final String TAG = "VisioLingo";

    // === Configuration ======================================================================
    // Clé chargée depuis local.properties (fichier en git ignore) via BuildConfig
    private static final String API_KEY = BuildConfig.OPENAI_API_KEY;

    private static final String CALLS_URL = "https://api.openai.com/v1/realtime/calls"; // endpoint de l'API
    private static final String MODEL = "gpt-realtime-2"; // nom du modèle
    private static final String VOICE = "marin";
    private static final String DATA_CHANNEL_LABEL = "oai-events";
    private static final long ICE_GATHERING_TIMEOUT_MS = 3000;

    private static final String INSTRUCTIONS =
            "Tu es VisioLingo, un coach linguistique vocal. L'utilisateur porte des lunettes a "
            + "camera : il regarde un objet et le decrit a voix haute dans la langue de son choix. "
            + "Juste avant chaque prise de parole, tu recois une image de ce qu'il regarde. "
            + "Sers-toi de cette image pour juger si sa description correspond bien a l'objet, "
            + "evaluer sa prononciation et son accent, corriger les erreurs et proposer une "
            + "formulation plus naturelle. Si l'objet ne correspond pas a ce qu'il dit, dis-lui "
            + "gentiment le bon mot. "
            + "REGLE DE LANGUE STRICTE : detecte la langue effectivement parlee par l'utilisateur "
            + "dans son audio, et reponds EXCLUSIVEMENT dans cette langue-la, quoi qu'il arrive. "
            + "N'utilise jamais une autre langue par defaut : si l'utilisateur parle anglais, "
            + "reponds en anglais ; s'il parle espagnol, reponds en espagnol ; etc. "
            + "SOIS EXIGEANT ET STRICT dans ton evaluation, sans complaisance : juge finement la "
            + "prononciation et l'accent (voyelles, intonation, accent tonique, liaisons, sons "
            + "specifiques a la langue) et signale clairement chaque defaut a l'oral, meme mineur. "
            + "Reste bref et constructif (1 a 3 phrases), mais ne survends pas : ne felicite que "
            + "ce qui est reellement bon. Si l'image est floue ou si tu n'as "
            + "pas compris, demande gentiment de repeter. "
            + "Lorsque l'on te demande d'appeler la fonction report_assessment, renseigne "
            + ": keyword (le mot principal designant l'objet, dans la langue parlee par "
            + "l'utilisateur), pronunciation_score (entier de 0 a 10 evaluant la prononciation et "
            + "l'accent), content_score (entier de 0 a 10 evaluant la justesse et la pertinence de "
            + "la description par rapport a l'objet reellement vu sur l'image). "
            + "NOTE AVEC EXIGENCE, selon le bareme : 9-10 = niveau quasi natif/parfait, "
            + "7-8 = bon mais defauts perceptibles, 5-6 = comprehensible avec erreurs nettes, "
            + "3-4 = difficile a comprendre, 0-2 = incorrect. Reserve 9-10 a l'excellence reelle "
            + "et n'hesite pas a mettre des notes basses quand c'est justifie. Enfin flag_country_code "
            + "(code pays ISO 3166-1 alpha-2 en majuscules le plus representatif de la langue "
            + "parlee, ex : FR, GB, ES, DE, IT, JP).";

    // === Callback vers l'UI =================================================================
    // est implémenté par MainActivity
    public interface Listener {
        void onStatus(String status);
        void onUserTranscript(String text);
        void onAssistantTranscript(String text);
        void onError(String message);
        void onUserSpeechStarted();
        void onAssessment(String keyword, int pronunciationScore, int contentScore,
                          String flagCountryCode);
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService httpExecutor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();
    private final AtomicBoolean offerSent = new AtomicBoolean(false);
    private final StringBuilder assistantBuffer = new StringBuilder();

    private static boolean factoryInitialized = false;

    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule audioDeviceModule;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private AudioManager audioManager;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private AudioTrack remoteAudioTrack;
    private AudioTrackSink remoteSink;
    private WavRecorder wavRecorder;
    private final ExecutorService recorderExec = Executors.newSingleThreadExecutor();
    private volatile boolean closed = false;
    private boolean statsStarted = false;
    private volatile boolean pendingReport = false;

    /** Logue toutes les 2 s le niveau du micro local et les octets audio envoyes a OpenAI. */
    private final Runnable statsLogger = new Runnable() {
        @Override
        public void run() {
            final PeerConnection pc = peerConnection;
            if (pc == null || closed) return;
            pc.getStats(new RTCStatsCollectorCallback() {
                @Override
                public void onStatsDelivered(RTCStatsReport report) {
                    for (RTCStats s : report.getStatsMap().values()) {
                        if ("media-source".equals(s.getType())
                                && "audio".equals(String.valueOf(s.getMembers().get("kind")))) {
                            Log.d(TAG, "MIC level=" + s.getMembers().get("audioLevel")
                                    + " energy=" + s.getMembers().get("totalAudioEnergy"));
                        } else if ("outbound-rtp".equals(s.getType())
                                && "audio".equals(String.valueOf(s.getMembers().get("kind")))) {
                            Log.d(TAG, "OUT bytesSent=" + s.getMembers().get("bytesSent")
                                    + " packetsSent=" + s.getMembers().get("packetsSent"));
                        }
                    }
                }
            });
            mainHandler.postDelayed(this, 2000);
        }
    };

    public RealtimeClient(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    // === Cycle de vie =======================================================================

    public void connect() {
        notifyStatus("Initialisation WebRTC…");
        configureAudioForCall();
        initFactory();

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(config, pcObserver);
        if (peerConnection == null) {
            notifyError("Echec de creation de la PeerConnection");
            return;
        }

        // Piste audio locale (micro). En Unified Plan, addTrack cree un transceiver sendrecv,
        // donc on recevra aussi l'audio de GPT.
        MediaConstraints audioConstraints = new MediaConstraints();
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream0"));

        // Data channel pour les events JSON OpenAI.
        DataChannel.Init dcInit = new DataChannel.Init();
        dataChannel = peerConnection.createDataChannel(DATA_CHANNEL_LABEL, dcInit);
        dataChannel.registerObserver(dcObserver);

        // Cree l'offre puis fixe la description locale ; le POST partira a la fin du gathering ICE.
        peerConnection.createOffer(new SimpleSdpObserver("createOffer") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SimpleSdpObserver("setLocal"), sdp);
            }
        }, new MediaConstraints());

        // Filet de securite : si onIceGatheringChange(COMPLETE) tarde, on poste quand meme.
        mainHandler.postDelayed(this::maybeSendOffer, ICE_GATHERING_TIMEOUT_MS);
    }

    public void close() {
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (dataChannel != null) {
            try { dataChannel.unregisterObserver(); } catch (Exception ignored) {}
            dataChannel.dispose();
            dataChannel = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }
        if (remoteAudioTrack != null && remoteSink != null) {
            try { remoteAudioTrack.removeSink(remoteSink); } catch (Exception ignored) {}
        }
        remoteSink = null;
        remoteAudioTrack = null;
        final WavRecorder rec = wavRecorder;
        wavRecorder = null;
        if (rec != null) {
            recorderExec.execute(rec::stop); // s'execute apres les append() en file
        }
        recorderExec.shutdown();
        if (localAudioTrack != null) { localAudioTrack.dispose(); localAudioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (audioDeviceModule != null) { audioDeviceModule.release(); audioDeviceModule = null; }
        restoreAudioMode();
        httpExecutor.shutdownNow();
    }

    // === WebRTC factory =====================================================================

    //
    // Passe l'appareil en mode communication (type VoIP) pour obtenir la priorite micro face a
    // l'assistant vocal always-on des lunettes Vuzix, et route la sortie sur le haut-parleur.
    //
    private void configureAudioForCall() {
        try {
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                previousAudioMode = audioManager.getMode();
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
                Log.d(TAG, "Audio mode -> IN_COMMUNICATION (was " + previousAudioMode + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "configureAudioForCall failed", e);
        }
    }

    private void restoreAudioMode() {
        try {
            if (audioManager != null) {
                audioManager.setMode(previousAudioMode);
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Demarre l'enregistrement de la voix de GPT (piste audio distante) dans un fichier WAV,
     * pour reutilisation au montage video. Fichier dans le dossier externe de l'appli :
     * /sdcard/Android/data/<pkg>/files/visiolingo_gpt_<horodatage>.wav (recuperable via adb pull).
     */
    private void startRecording(AudioTrack remoteTrack) {
        if (wavRecorder != null) return; // une seule session d'enregistrement
        File dir = appContext.getExternalFilesDir(null);
        String name = "visiolingo_gpt_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
        final WavRecorder rec = new WavRecorder(new File(dir, name));
        wavRecorder = rec;
        remoteAudioTrack = remoteTrack;
        remoteSink = new AudioTrackSink() {
            @Override
            public void onData(ByteBuffer audioData, int bitsPerSample, int sampleRate,
                               int numberOfChannels, int numberOfFrames,
                               long absoluteCaptureTimestampMs) {
                int len = audioData.remaining();
                if (len <= 0) return;
                byte[] buf = new byte[len];
                audioData.duplicate().get(buf);
                recorderExec.execute(
                        () -> rec.append(buf, sampleRate, numberOfChannels, bitsPerSample));
            }
        };
        remoteTrack.addSink(remoteSink);
        Log.d(TAG, "Enregistrement voix GPT -> " + rec.getFile().getAbsolutePath());
        mainHandler.postDelayed(wavFlusher, 3000);
    }

    /** Reecrit periodiquement l'en-tete WAV pour que le fichier reste lisible meme sans fermer. */
    private final Runnable wavFlusher = new Runnable() {
        @Override
        public void run() {
            final WavRecorder rec = wavRecorder;
            if (rec == null || closed) return;
            try { recorderExec.execute(rec::flush); } catch (Exception ignored) {}
            mainHandler.postDelayed(this, 3000);
        }
    };

    private void initFactory() {
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .createInitializationOptions());
            factoryInitialized = true;
        }
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                // Micro brut + AEC/NS materiel desactives : sur le M4000 en MODE_NORMAL, le
                // traitement VOICE_COMMUNICATION + AEC HW semblait annuler l'entree micro.
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback() {
                    @Override public void onWebRtcAudioRecordStart() {
                        Log.d(TAG, "MIC: recording started");
                    }
                    @Override public void onWebRtcAudioRecordStop() {
                        Log.d(TAG, "MIC: recording stopped");
                    }
                })
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override public void onWebRtcAudioRecordInitError(String msg) {
                        notifyError("MIC init error: " + msg);
                    }
                    @Override public void onWebRtcAudioRecordStartError(
                            JavaAudioDeviceModule.AudioRecordStartErrorCode code, String msg) {
                        notifyError("MIC start error (" + code + "): " + msg);
                    }
                    @Override public void onWebRtcAudioRecordError(String msg) {
                        notifyError("MIC error: " + msg);
                    }
                })
                .setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback() {
                    @Override public void onWebRtcAudioTrackStart() {
                        Log.d(TAG, "SPEAKER: playout started");
                    }
                    @Override public void onWebRtcAudioTrackStop() {
                        Log.d(TAG, "SPEAKER: playout stopped");
                    }
                })
                .createAudioDeviceModule();
        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory();
    }

    // === Signaling SDP ======================================================================

    /** Poste l'offre locale (avec candidats ICE) vers OpenAI, une seule fois. */
    private void maybeSendOffer() {
        if (closed || !offerSent.compareAndSet(false, true)) {
            return;
        }
        if (peerConnection == null || peerConnection.getLocalDescription() == null) {
            notifyError("Offre SDP indisponible");
            return;
        }
        final String offerSdp = peerConnection.getLocalDescription().description;
        notifyStatus("Connexion a OpenAI…");

        httpExecutor.execute(() -> {
            try {
                JSONObject session = new JSONObject();
                session.put("type", "realtime");
                session.put("model", MODEL);
                session.put("audio", new JSONObject()
                        .put("output", new JSONObject().put("voice", VOICE)));

                RequestBody body = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("sdp", offerSdp)
                        .addFormDataPart("session", session.toString())
                        .build();

                Request request = new Request.Builder()
                        .url(CALLS_URL)
                        .addHeader("Authorization", "Bearer " + API_KEY)
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, java.io.IOException e) {
                        notifyError("POST SDP echoue : " + e.getMessage());
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        try (Response r = response) {
                            String bodyStr = r.body() != null ? r.body().string() : "";
                            if (!r.isSuccessful()) {
                                notifyError("OpenAI HTTP " + r.code() + " : " + bodyStr);
                                return;
                            }
                            SessionDescription answer = new SessionDescription(
                                    SessionDescription.Type.ANSWER, bodyStr);
                            if (peerConnection != null) {
                                peerConnection.setRemoteDescription(
                                        new SimpleSdpObserver("setRemote"), answer);
                            }
                        } catch (Exception e) {
                            notifyError("Reponse SDP invalide : " + e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                notifyError("Erreur signaling : " + e.getMessage());
            }
        });
    }

    // === Events OpenAI (data channel) =======================================================

    /** Envoie la configuration de session des l'ouverture du data channel. */
    private void sendSessionUpdate() {
        try {
            JSONObject turnDetection = new JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.5)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 500)
                    .put("create_response", true);
            JSONObject input = new JSONObject()
                    .put("turn_detection", turnDetection)
                    .put("transcription", new JSONObject().put("model", "whisper-1"));
            JSONObject audio = new JSONObject()
                    .put("input", input)
                    .put("output", new JSONObject().put("voice", VOICE));
            JSONObject sessionObj = new JSONObject()
                    .put("type", "realtime")
                    .put("instructions", INSTRUCTIONS)
                    .put("output_modalities", new JSONArray().put("audio"))
                    .put("audio", audio)
                    .put("tools", buildTools())
                    .put("tool_choice", "none");
            JSONObject event = new JSONObject()
                    .put("type", "session.update")
                    .put("session", sessionObj);
            sendEvent(event);
            Log.d(TAG, "session.update sent (server_vad)");
        } catch (Exception e) {
            notifyError("session.update invalide : " + e.getMessage());
        }
    }

    // fonction report_assessment, la même que promptée au modèle
    private JSONArray buildTools() throws Exception {
        JSONObject props = new JSONObject()
                .put("keyword", new JSONObject()
                        .put("type", "string")
                        .put("description", "Mot principal designant l'objet, dans la langue parlee."))
                .put("pronunciation_score", new JSONObject()
                        .put("type", "integer")
                        .put("minimum", 0)
                        .put("maximum", 10)
                        .put("description", "Note de prononciation/accent de 0 a 10."))
                .put("content_score", new JSONObject()
                        .put("type", "integer")
                        .put("minimum", 0)
                        .put("maximum", 10)
                        .put("description", "Note de justesse/pertinence de la description de 0 a 10."))
                .put("flag_country_code", new JSONObject()
                        .put("type", "string")
                        .put("description", "Code pays ISO 3166-1 alpha-2 (majuscules) de la langue parlee."));
        JSONObject params = new JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", new JSONArray()
                        .put("keyword").put("pronunciation_score")
                        .put("content_score").put("flag_country_code"));
        JSONObject tool = new JSONObject()
                .put("type", "function")
                .put("name", "report_assessment")
                .put("description", "Renvoie une evaluation structuree de la description de l'utilisateur.")
                .put("parameters", params);
        return new JSONArray().put(tool);
    }

    private void requestAssessment() {
        try {
            JSONObject toolChoice = new JSONObject()
                    .put("type", "function")
                    .put("name", "report_assessment");
            JSONObject response = new JSONObject()
                    .put("output_modalities", new JSONArray().put("text"))
                    .put("tool_choice", toolChoice);
            JSONObject event = new JSONObject()
                    .put("type", "response.create")
                    .put("response", response);
            sendEvent(event);
            Log.d(TAG, "response.create (report_assessment force)");
        } catch (Exception e) {
            notifyError("requestAssessment: " + e.getMessage());
        }
    }

    private void sendFunctionCallOutput(String callId) {
        if (callId == null || callId.isEmpty()) return;
        try {
            JSONObject item = new JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", "ok");
            JSONObject event = new JSONObject()
                    .put("type", "conversation.item.create")
                    .put("item", item);
            sendEvent(event);
        } catch (Exception e) {
            Log.w(TAG, "function_call_output", e);
        }
    }

    private void sendEvent(JSONObject event) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) return;
        byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    // Ajoute une image JPEG au contexte de la conversation (event conversation.item.create avec
    // un contenu input_image en data URL base64)
    public void sendImage(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) {
            Log.d(TAG, "image ignoree (data channel non ouvert)");
            return;
        }
        try {
            String dataUrl = "data:image/jpeg;base64,"
                    + Base64.encodeToString(jpeg, Base64.NO_WRAP);
            JSONObject content = new JSONObject()
                    .put("type", "input_image")
                    .put("image_url", dataUrl);
            JSONObject item = new JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", new JSONArray().put(content));
            JSONObject event = new JSONObject()
                    .put("type", "conversation.item.create")
                    .put("item", item);
            sendEvent(event);
            Log.d(TAG, "image envoyee (" + jpeg.length + " octets)");
        } catch (Exception e) {
            notifyError("sendImage: " + e.getMessage());
        }
    }

    private void handleEvent(String json) {
        try {
            JSONObject event = new JSONObject(json);
            String type = event.optString("type", "");
            Log.d(TAG, "evt <= " + type);
            switch (type) {
                case "session.created":
                    notifyStatus("Connecte — a l'ecoute");
                    break;
                case "session.updated":
                    Log.d(TAG, "session.updated");
                    break;
                case "input_audio_buffer.speech_started":
                    notifyStatus("🎤 …");
                    mainHandler.post(listener::onUserSpeechStarted);
                    break;
                case "input_audio_buffer.speech_stopped":
                    notifyStatus("Connecte — a l'ecoute");
                    pendingReport = true; // ce tour devra produire une evaluation
                    break;
                case "conversation.item.input_audio_transcription.completed": {
                    String t = event.optString("transcript", "").trim();
                    if (!t.isEmpty()) notifyUser(t);
                    break;
                }
                case "response.output_audio_transcript.delta":
                    assistantBuffer.append(event.optString("delta", ""));
                    break;
                case "response.output_audio_transcript.done": {
                    String full = assistantBuffer.toString().trim();
                    assistantBuffer.setLength(0);
                    if (!full.isEmpty()) notifyAssistant(full);
                    break;
                }
                case "response.done": {
                    String full = assistantBuffer.toString().trim();
                    assistantBuffer.setLength(0);
                    if (!full.isEmpty()) notifyAssistant(full);
                    // La reponse audio du tour est finie -> on demande l'evaluation structuree.
                    if (pendingReport) {
                        pendingReport = false;
                        requestAssessment();
                    }
                    break;
                }
                case "response.function_call_arguments.done": {
                    handleFunctionCall(event);
                    break;
                }
                case "error": {
                    Log.e(TAG, "error event: " + json);
                    JSONObject err = event.optJSONObject("error");
                    notifyError(err != null ? err.optString("message", json) : json);
                    break;
                }
                default:
                    Log.d(TAG, "event: " + type);
            }
        } catch (Exception e) {
            Log.w(TAG, "Event illisible : " + json, e);
        }
    }

    /** Parse les arguments JSON de report_assessment et remonte l'evaluation a l'UI. */
    private void handleFunctionCall(JSONObject event) {
        String args = event.optString("arguments", "");
        if (args.isEmpty()) return;
        try {
            JSONObject a = new JSONObject(args);
            String keyword = a.optString("keyword", "").trim();
            int score = a.optInt("pronunciation_score", -1);
            int contentScore = a.optInt("content_score", -1);
            String flag = a.optString("flag_country_code", "").trim().toUpperCase(Locale.US);
            notifyAssessment(keyword, score, contentScore, flag);
            sendFunctionCallOutput(event.optString("call_id", ""));
        } catch (Exception e) {
            Log.w(TAG, "report_assessment illisible : " + args, e);
        }
    }

    // === Observers ==========================================================================

    private final PeerConnection.Observer pcObserver = new PeerConnection.Observer() {
        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            Log.d(TAG, "ICE gathering: " + state);
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                maybeSendOffer();
            }
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.d(TAG, "ICE connection: " + state);
            if (state == PeerConnection.IceConnectionState.FAILED) {
                notifyError("Connexion ICE echouee");
            }
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            Log.d(TAG, "PC state: " + state);
            if (state == PeerConnection.PeerConnectionState.CONNECTED && !statsStarted) {
                statsStarted = true;
                mainHandler.post(statsLogger);
            }
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            MediaStreamTrack track = receiver.track();
            if (track instanceof AudioTrack) {
                AudioTrack at = (AudioTrack) track;
                at.setEnabled(true);
                Log.d(TAG, "Piste audio distante recue");
                startRecording(at);
            }
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceCandidate(IceCandidate candidate) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel dc) {}
        @Override public void onRenegotiationNeeded() {}
    };

    private final DataChannel.Observer dcObserver = new DataChannel.Observer() {
        @Override
        public void onStateChange() {
            if (dataChannel == null) return;
            DataChannel.State state = dataChannel.state();
            Log.d(TAG, "DataChannel: " + state);
            if (state == DataChannel.State.OPEN) {
                sendSessionUpdate();
            }
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            ByteBuffer data = buffer.data;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            handleEvent(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void onBufferedAmountChange(long previousAmount) {}
    };

    /** SdpObserver minimal qui logue les echecs. */
    private class SimpleSdpObserver implements SdpObserver {
        private final String tag;
        SimpleSdpObserver(String tag) { this.tag = tag; }
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { notifyError(tag + " : " + error); }
        @Override public void onSetFailure(String error) { notifyError(tag + " : " + error); }
    }

    // === Notifications UI (thread principal) ================================================

    private void notifyStatus(String s) { mainHandler.post(() -> listener.onStatus(s)); }
    private void notifyUser(String s) { mainHandler.post(() -> listener.onUserTranscript(s)); }
    private void notifyAssistant(String s) { mainHandler.post(() -> listener.onAssistantTranscript(s)); }
    private void notifyAssessment(String keyword, int score, int contentScore, String flag) {
        mainHandler.post(() -> listener.onAssessment(keyword, score, contentScore, flag));
    }
    private void notifyError(String s) {
        Log.e(TAG, s);
        mainHandler.post(() -> listener.onError(s));
    }
}
