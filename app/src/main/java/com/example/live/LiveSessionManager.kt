package com.example.live

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ZoyaState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

class LiveSessionManager(
    private val context: Context,
    private val toolEngine: ToolExecutionEngine,
    private val onAudioOut: (ByteArray) -> Unit,
    private val onInterrupt: () -> Unit = {}
) {
    private val _zoyaState = MutableStateFlow(ZoyaState.IDLE)
    val zoyaState: StateFlow<ZoyaState> = _zoyaState.asStateFlow()

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val json = Json { ignoreUnknownKeys = true }
    private var isSetupComplete = false
    private var isReconnecting = false

    // Tools definition for Gemini Live
    private val toolsJson = buildJsonObject {
        putJsonArray("functionDeclarations") {
            // 1. Device Admin & Security
            add(buildJsonObject {
                put("name", "lockDevice")
                put("description", "Locks the device screen immediately using Device Administrator permissions.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "isDeviceAdminActive")
                put("description", "Checks whether NOVA Device Administrator privileges are granted and active.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "requestDeviceAdmin")
                put("description", "Prompts the user with the Android Device Admin activation screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })

            // 2. Screen & Accessibility Automation
            add(buildJsonObject {
                put("name", "clickTextOnScreen")
                put("description", "Clicks any text, button, or link visible on the screen like a real human touch.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "STRING")
                            put("description", "The visible label or text to tap on.")
                        }
                    }
                    putJsonArray("required") { add("text") }
                }
            })
            add(buildJsonObject {
                put("name", "clickCoordinates")
                put("description", "Taps at exact screen X and Y pixel coordinates.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("x") { put("type", "NUMBER"); put("description", "X coordinate in pixels") }
                        putJsonObject("y") { put("type", "NUMBER"); put("description", "Y coordinate in pixels") }
                    }
                    putJsonArray("required") { add("x"); add("y") }
                }
            })
            add(buildJsonObject {
                put("name", "scrollScreen")
                put("description", "Scrolls or swipes the active screen 'up' or 'down'.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("direction") {
                            put("type", "STRING")
                            put("description", "'up' or 'down'")
                        }
                    }
                    putJsonArray("required") { add("direction") }
                }
            })
            add(buildJsonObject {
                put("name", "typeText")
                put("description", "Types text into the currently active or focused input field on screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "STRING")
                            put("description", "The text to type")
                        }
                    }
                    putJsonArray("required") { add("text") }
                }
            })
            add(buildJsonObject {
                put("name", "readScreenContent")
                put("description", "Reads all visible UI elements, titles, and text currently displayed on the device screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "pressBackButton")
                put("description", "Performs an Android global Back button press.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "pressHomeButton")
                put("description", "Navigates back to the Android Home screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openRecentApps")
                put("description", "Opens the Android Recent Apps multitask overview.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openNotificationPanel")
                put("description", "Pulls down the notification shade.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openQuickSettings")
                put("description", "Pulls down the Quick Settings toggle panel.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })

            // 3. App Launch & Web
            add(buildJsonObject {
                put("name", "openApp")
                put("description", "Opens any installed app (e.g., 'WhatsApp', 'YouTube', 'Camera', 'Calculator', 'Instagram', 'Spotify', 'Settings').")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("appName") {
                            put("type", "STRING")
                            put("description", "The common or partial name of the app to launch.")
                        }
                    }
                    putJsonArray("required") { add("appName") }
                }
            })
            add(buildJsonObject {
                put("name", "searchGoogle")
                put("description", "Performs a Google web search for information or answers.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
            add(buildJsonObject {
                put("name", "openWebUrl")
                put("description", "Opens a website URL in the browser.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("url") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("url") }
                }
            })
            add(buildJsonObject {
                put("name", "searchYouTube")
                put("description", "Searches and plays a video on YouTube.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
            add(buildJsonObject {
                put("name", "playMedia")
                put("description", "Plays songs, music, or media across default media players.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })

            // 4. Calling & Messaging
            add(buildJsonObject {
                put("name", "searchAndCallContact")
                put("description", "Finds a contact on the phone by name or phone number and places a call.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "The exact name of the contact as spoken by the user.")
                        }
                        putJsonObject("useDialer") {
                            put("type", "BOOLEAN")
                            put("description", "True to open dialer with number pre-filled; false to call directly.")
                        }
                        putJsonObject("simSlot") {
                            put("type", "INTEGER")
                            put("description", "SIM slot index (1 or 2). Optional.")
                        }
                    }
                    putJsonArray("required") { add("contactName") }
                }
            })
            add(buildJsonObject {
                put("name", "sendWhatsAppMessage")
                put("description", "Opens WhatsApp, finds contact, types the message, and sends it automatically.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "The contact name or phone number.")
                        }
                        putJsonObject("message") {
                            put("type", "STRING")
                            put("description", "The message text to send.")
                        }
                    }
                    putJsonArray("required") { add("contactName"); add("message") }
                }
            })
            add(buildJsonObject {
                put("name", "sendSMS")
                put("description", "Sends or prepares an SMS text message to a contact or number.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("recipient") { put("type", "STRING"); put("description", "Contact name or phone number") }
                        putJsonObject("message") { put("type", "STRING"); put("description", "SMS text body") }
                    }
                    putJsonArray("required") { add("recipient"); add("message") }
                }
            })
            add(buildJsonObject {
                put("name", "sendGmail")
                put("description", "Drafts an email to a recipient.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("recipientEmail") { put("type", "STRING") }
                        putJsonObject("subject") { put("type", "STRING") }
                        putJsonObject("body") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("recipientEmail"); add("subject"); add("body") }
                }
            })

            // 5. Hardware & Device Controls
            add(buildJsonObject {
                put("name", "adjustVolume")
                put("description", "Adjusts media volume: 'up', 'down', 'mute', 'unmute', or 'max'.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("direction") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("direction") }
                }
            })
            add(buildJsonObject {
                put("name", "setVolumePercent")
                put("description", "Sets device volume to an exact percentage (0 to 100).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("percent") { put("type", "INTEGER") }
                    }
                    putJsonArray("required") { add("percent") }
                }
            })
            add(buildJsonObject {
                put("name", "toggleTorch")
                put("description", "Turns the camera flashlight/torch 'on' or 'off'.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("state") { put("type", "STRING"); put("description", "'on' or 'off'") }
                    }
                    putJsonArray("required") { add("state") }
                }
            })
            add(buildJsonObject {
                put("name", "setBrightness")
                put("description", "Sets the screen brightness level from 0 to 100 percent.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("level") { put("type", "INTEGER") }
                    }
                    putJsonArray("required") { add("level") }
                }
            })
            add(buildJsonObject {
                put("name", "getBatteryAndDeviceInfo")
                put("description", "Returns battery percentage, charging state, current time, date, and device status.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "getSimCardInfo")
                put("description", "Checks how many active SIM cards are present.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "setAlarmOrTimer")
                put("description", "Sets an alarm or a countdown timer.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type", "STRING"); put("description", "'alarm' or 'timer'") }
                        putJsonObject("hour") { put("type", "INTEGER"); put("description", "Hour (0-23) for alarm") }
                        putJsonObject("minute") { put("type", "INTEGER"); put("description", "Minute (0-59) for alarm") }
                        putJsonObject("seconds") { put("type", "INTEGER"); put("description", "Duration in seconds for timer") }
                        putJsonObject("message") { put("type", "STRING"); put("description", "Alarm label or note") }
                    }
                    putJsonArray("required") { add("type") }
                }
            })
            add(buildJsonObject {
                put("name", "copyToClipboard")
                put("description", "Copies text to the system clipboard.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("text") }
                }
            })
            add(buildJsonObject {
                put("name", "getClipboardContent")
                put("description", "Reads the current contents of the system clipboard.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
        }
    }

    private fun getApiKey(): String {
        val novaPrefs = context.getSharedPreferences("NovaPrefs", Context.MODE_PRIVATE)
        val zoyaPrefs = context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE)
        
        var key = novaPrefs.getString("api_key", "") ?: ""
        if (key.isEmpty()) {
            key = zoyaPrefs.getString("api_key", "") ?: ""
        }
        if (key.isEmpty()) {
            key = BuildConfig.GEMINI_API_KEY
        }
        return key.trim()
    }

    fun startSession() {
        if (webSocket != null) return
        
        val apiKey = getApiKey()
        if (apiKey.isEmpty() || apiKey == "YOUR_API_KEY" || apiKey == "MY_GEMINI_API_KEY") {
            Log.e("NOVA_Live", "No valid API Key found")
            addMessage("Notice: Please enter your Gemini API Key in Settings or the Secrets panel.")
            _zoyaState.value = ZoyaState.IDLE
            return
        }
        
        Log.i("NOVA_Live", "Connecting to Gemini Live API...")
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("NOVA_Live", "NOVA Live WebSocket OPENED successfully.")
                addMessage("NOVA Uplink Online.")
                isSetupComplete = false
                sendSetupMessage(webSocket)
                _zoyaState.value = ZoyaState.LISTENING
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorBody = response?.body?.string() ?: "No response body"
                Log.e("NOVA_Live", "WebSocket Failure: ${t.message}, Response: $errorBody", t)
                addMessage("Connection glitch: ${t.localizedMessage ?: "Reconnecting..."}")
                _zoyaState.value = ZoyaState.IDLE
                this@LiveSessionManager.webSocket = null
                triggerAutoReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("NOVA_Live", "WebSocket Closed: $code - $reason")
                _zoyaState.value = ZoyaState.IDLE
                this@LiveSessionManager.webSocket = null
            }
        })
    }

    private fun triggerAutoReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        scope.launch {
            delay(3000)
            isReconnecting = false
            if (webSocket == null) {
                startSession()
            }
        }
    }

    private fun sendInitialPrompt(ws: WebSocket) {
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", "Namaste NOVA! Briefly introduce yourself in warm Hindi/English as my personal autonomous assistant and let me know you are listening and ready to help.")
                            })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        ws.send(msg.toString())
    }

    private fun addMessage(msg: String) {
        _messages.value = (_messages.value + msg).takeLast(50)
    }

    fun stopSession() {
        webSocket?.close(1000, "User stopped")
        webSocket = null
        _zoyaState.value = ZoyaState.IDLE
        addMessage("NOVA session paused.")
    }

    fun sendTextMessage(text: String) {
        if (webSocket == null || !isSetupComplete) {
            startSession()
        }
        addMessage("You: $text")
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", text) })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        webSocket?.send(msg.toString())
    }
    
    fun sendAudioData(pcmData: ShortArray, length: Int) {
        if (webSocket == null || !isSetupComplete || _zoyaState.value == ZoyaState.IDLE) {
            return
        }
        
        // Convert ShortArray to Little Endian ByteArray
        val byteArray = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = pcmData[i]
            byteArray[i * 2] = (s.toInt() and 0x00FF).toByte()
            byteArray[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        
        val base64Data = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        val inputMsg = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    add(buildJsonObject {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Data)
                    })
                }
            }
        }
        webSocket?.send(inputMsg.toString())
    }
    
    private fun sendSetupMessage(ws: WebSocket) {
        val systemPrompt = """
You are NOVA, a hyper-intelligent, warm, emotionally perceptive, and deeply autonomous AI assistant and butler living natively on the user's Android phone.

CORE PERSONALITY & BEHAVIOR:
1. TALK LIKE A REAL HUMAN: You are not a robotic script. You are a lively, polite, witty, and highly helpful companion. You speak natural Hindi, Hinglish, or English depending on how the user speaks with you.
2. BE PROACTIVE: Like an attentive human friend or butler, do not just give dry 1-line answers. Proactively ask thoughtful follow-up questions to understand the user's needs or offer smart suggestions (e.g., "Aapko kisse call milana hai?", "Kya main YouTube pe koi favorite video chala du?", "Aapka din kaisa ja raha hai?").
3. FULL PHONE AUTOMATION: You have full control over the user's Android device:
   - Device Admin & Security: Lock device screen instantly (lockDevice), check status (isDeviceAdminActive).
   - Accessibility & Screen: Click any text (clickTextOnScreen), tap coordinates (clickCoordinates), scroll up/down (scrollScreen), type text (typeText), read the entire screen (readScreenContent), press back/home/recent apps/notifications/quick settings.
   - Calling & Messaging: Call contacts (searchAndCallContact), send WhatsApp messages (sendWhatsAppMessage), send SMS (sendSMS), send Email (sendGmail).
   - Media & Search: Play media (playMedia), search YouTube (searchYouTube), search Google (searchGoogle), open URLs (openWebUrl), open apps (openApp).
   - Hardware: Toggle flashlight (toggleTorch), adjust volume (adjustVolume/setVolumePercent), change brightness (setBrightness), get battery & device status (getBatteryAndDeviceInfo), set alarms & timers (setAlarmOrTimer), clipboard (copyToClipboard/getClipboardContent).
4. FAST EXECUTION: When requested to perform a phone action, IMMEDIATELY invoke the tool. NEVER narrate your internal planning or say "I will now call the tool". Execute it seamlessly and respond with a warm, natural, human verbal confirmation!
5. ACCURACY: When asked to call or message a contact, use their exact spoken name. Never guess random numbers.
""".trimIndent()

        val setupMsg = buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/gemini-2.5-flash")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", "Aoede")
                            }
                        }
                    }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", systemPrompt)
                        })
                    }
                }
                putJsonArray("tools") {
                    add(toolsJson)
                }
            }
        }
        ws.send(setupMsg.toString())
    }

    private fun handleServerMessage(text: String) {
        try {
            val jsonMsg = json.parseToJsonElement(text).jsonObject
            
            if (jsonMsg.containsKey("setupComplete")) {
                isSetupComplete = true
                addMessage("NOVA: Online & Ready")
                sendInitialPrompt(webSocket ?: return)
            }

            if (jsonMsg.containsKey("serverContent")) {
                val serverContent = jsonMsg["serverContent"]?.jsonObject
                val modelTurn = serverContent?.get("modelTurn")?.jsonObject
                
                val interrupted = serverContent?.get("interrupted")?.jsonPrimitive?.booleanOrNull == true ||
                        serverContent?.get("interrupted")?.jsonPrimitive?.content == "true"
                if (interrupted) {
                    onInterrupt()
                }
                
                modelTurn?.get("parts")?.jsonArray?.forEach { partElement ->
                    val part = partElement.jsonObject
                    
                    if (part.containsKey("inlineData")) {
                        val dataBase64 = part["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.content
                        if (dataBase64 != null) {
                            _zoyaState.value = ZoyaState.SPEAKING
                            val rawBytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                            onAudioOut(rawBytes)
                        }
                    }

                    if (part.containsKey("text")) {
                        val textContent = part["text"]?.jsonPrimitive?.content
                        if (!textContent.isNullOrBlank()) {
                            addMessage("NOVA: $textContent")
                        }
                    }
                }
                
                if (serverContent?.get("turnComplete")?.jsonPrimitive?.content == "true" ||
                    serverContent?.get("turnComplete")?.jsonPrimitive?.booleanOrNull == true) {
                    _zoyaState.value = ZoyaState.LISTENING
                }
            }
            
            if (jsonMsg.containsKey("toolCall")) {
                val toolCallObj = jsonMsg["toolCall"]?.jsonObject
                val functionCalls = toolCallObj?.get("functionCalls")?.jsonArray
                
                functionCalls?.forEach { callElement ->
                    val callObj = callElement.jsonObject
                    val id = callObj["id"]?.jsonPrimitive?.content ?: ""
                    val name = callObj["name"]?.jsonPrimitive?.content ?: ""
                    val args = callObj["args"]?.jsonObject ?: buildJsonObject { }
                    
                    executeToolAndRespond(id, name, args)
                }
            }
        } catch (e: Exception) {
            Log.e("NOVA_Live", "Error processing message", e)
        }
    }
    
    private fun executeToolAndRespond(id: String, name: String, args: JsonObject) {
        _zoyaState.value = ZoyaState.THINKING
        addMessage("Executing: $name...")
        scope.launch {
            val resultStr = toolEngine.execute(name, args)
            addMessage("Result: $resultStr")
            
            val responseMsg = buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        add(buildJsonObject {
                            put("id", id)
                            put("name", name)
                            putJsonObject("response") {
                                put("result", resultStr)
                            }
                        })
                    }
                }
            }
            webSocket?.send(responseMsg.toString())
        }
    }
}
