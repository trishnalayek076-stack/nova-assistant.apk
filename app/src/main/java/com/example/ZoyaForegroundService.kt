package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.live.LiveSessionManager
import com.example.live.ZoyaState
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

class ZoyaForegroundService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    lateinit var liveSessionManager: LiveSessionManager
    private lateinit var toolEngine: ToolExecutionEngine

    private var isRecording = false

    // Configuration for Gemini Live Audio (16kHz, Mono, PCM 16-bit)
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Configuration for audio output (24kHz, Mono, PCM 16-bit)
    private val outputSampleRate = 24000
    private val outChannelConfig = AudioFormat.CHANNEL_OUT_MONO

    companion object {
        var currentState: ZoyaState = ZoyaState.IDLE
            private set
        var onStateChange: ((ZoyaState) -> Unit)? = null

        private val _messages = MutableStateFlow<List<String>>(emptyList())
        val messages: StateFlow<List<String>> = _messages.asStateFlow()

        private val _audioAmplitude = MutableStateFlow(0f)
        val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

        var activeService: ZoyaForegroundService? = null
    }

    override fun onCreate() {
        super.onCreate()
        try {
            activeService = this
            toolEngine = ToolExecutionEngine(this)

            // Acquire Partial WakeLock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NOVA::AudioWakeLock").apply {
                acquire(24 * 60 * 60 * 1000L) // 24h
            }

            val onAudioOut: (ByteArray) -> Unit = { audioData ->
                playAudio(audioData)
            }

            val onInterruptOut: () -> Unit = {
                try {
                    audioOutputQueue.clear()
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                    }
                } catch (e: Exception) {
                    Log.e("NOVA_Audio", "Error flushing track on interrupt", e)
                }
            }

            liveSessionManager = LiveSessionManager(this, toolEngine, onAudioOut, onInterruptOut)

            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (e: Exception) {
                    try { startForeground(1, createNotification()) } catch(e: Exception) { }
                }
            } else {
                try { startForeground(1, createNotification()) } catch(e: Exception) { }
            }

            scope.launch {
                liveSessionManager.zoyaState.collect { state ->
                    currentState = state
                    onStateChange?.invoke(state)
                }
            }
            scope.launch {
                liveSessionManager.messages.collect { msgList ->
                    _messages.value = msgList
                }
            }

            initAudioTrack()
            startMicrophoneLoop()
            liveSessionManager.startSession()
        } catch (e: Exception) {
            Log.e("NOVA_Service", "Error in onCreate", e)
        }
    }

    private val audioOutputQueue = LinkedBlockingQueue<ByteArray>()
    private var isAudioPlaybackActive = false

    private fun startAudioPlaybackLoop() {
        isAudioPlaybackActive = true
        scope.launch(Dispatchers.IO) {
            while (isActive && isAudioPlaybackActive) {
                try {
                    val data = audioOutputQueue.poll(50, TimeUnit.MILLISECONDS)
                    if (data != null) {
                        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack?.play()
                        }
                        audioTrack?.write(data, 0, data.size)
                    }
                } catch (e: Exception) {
                    Log.e("NOVA_Audio", "Playback loop error", e)
                }
            }
        }
    }

    private fun initAudioTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(outputSampleRate, outChannelConfig, audioFormat)
            val finalBuf = if (minBuf > 0) minBuf * 4 else 8192

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(outputSampleRate)
                        .setChannelMask(outChannelConfig)
                        .build()
                )
                .setBufferSizeInBytes(finalBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            startAudioPlaybackLoop()
        } catch (e: Exception) {
            Log.e("NOVA_Service", "Error initializing AudioTrack", e)
        }
    }

    private fun playAudio(data: ByteArray) {
        try {
            audioOutputQueue.offer(data)
        } catch (e: Exception) {
            Log.e("NOVA_Audio", "Error queueing audio", e)
        }
    }

    private fun startMicrophoneLoop() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("NOVA_Audio", "Missing RECORD_AUDIO permission")
            return
        }

        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val finalBuf = if (minBuf > 0) minBuf * 4 else 8192

            val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                createAttributionContext("nova_audio")
            } else {
                this
            }

            // Try VOICE_RECOGNITION first, fallback to MIC
            var record: AudioRecord? = null
            val audioSources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )

            for (src in audioSources) {
                try {
                    val candidate = AudioRecord.Builder()
                        .setContext(ctx)
                        .setAudioSource(src)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .setEncoding(audioFormat)
                                .build()
                        )
                        .setBufferSizeInBytes(finalBuf)
                        .build()

                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        Log.i("NOVA_Audio", "Initialized AudioRecord with source $src")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.w("NOVA_Audio", "Failed initializing audio source $src", e)
                }
            }

            audioRecord = record
            if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("NOVA_Audio", "AudioRecord could not be initialized!")
                return
            }

            // Attach Audio Effects if available
            val sessionId = audioRecord!!.audioSessionId
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                }
            } catch (e: Exception) {
                Log.w("NOVA_Audio", "AudioFX init skipped: ${e.message}")
            }

            audioRecord?.startRecording()
            isRecording = true

            scope.launch(Dispatchers.IO) {
                // 100ms chunks: 1600 samples at 16kHz
                val chunkSize = 1600
                val audioBuffer = ShortArray(chunkSize)
                val boostedBuffer = ShortArray(chunkSize)
                val gainFactor = 1.6f // Boost mic sensitivity so speech is never missed

                while (isActive && isRecording) {
                    try {
                        val readResult = audioRecord?.read(audioBuffer, 0, chunkSize) ?: 0
                        if (readResult > 0) {
                            var sumSquares = 0.0
                            for (i in 0 until readResult) {
                                val sample = audioBuffer[i]
                                sumSquares += (sample * sample).toDouble()
                                
                                // Software gain boost with clipping guard
                                val boosted = (sample * gainFactor).toInt()
                                boostedBuffer[i] = boosted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                            
                            val rms = sqrt(sumSquares / readResult)
                            val normalizedAmp = (rms / 8000.0).coerceIn(0.0, 1.0).toFloat()
                            _audioAmplitude.value = normalizedAmp

                            processAudio(boostedBuffer, readResult)
                        }
                    } catch (e: Exception) {
                        Log.e("NOVA_Audio", "Error in microphone read loop", e)
                    }
                }
                Log.i("NOVA_Audio", "Microphone recording loop exited.")
            }
        } catch (e: Exception) {
            Log.e("NOVA_Audio", "Error starting microphone", e)
        }
    }

    private var lastReconnectCheck = 0L

    private fun processAudio(buffer: ShortArray, length: Int) {
        val state = liveSessionManager.zoyaState.value

        if (state != ZoyaState.IDLE) {
            liveSessionManager.sendAudioData(buffer, length)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastReconnectCheck > 3000) {
                lastReconnectCheck = now
                liveSessionManager.startSession()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun sendTextMessage(text: String) {
        liveSessionManager.sendTextMessage(text)
    }

    fun reconnectSession() {
        liveSessionManager.startSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeService = null
        isRecording = false
        isAudioPlaybackActive = false
        currentState = ZoyaState.IDLE
        onStateChange?.invoke(currentState)
        _audioAmplitude.value = 0f
        audioOutputQueue.clear()

        try { noiseSuppressor?.release() } catch (e: Exception) {}
        try { gainControl?.release() } catch (e: Exception) {}
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        try { audioTrack?.stop() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        try { liveSessionManager.stopSession() } catch (e: Exception) {}
        try { wakeLock?.release() } catch (e: Exception) {}
        job.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "NOVA_CHANNEL",
                "NOVA Assistant Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps NOVA listening for voice commands in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "NOVA_CHANNEL")
            .setContentTitle("NOVA is active & listening")
            .setContentText("Autonomous AI Butler ready for voice commands.")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
