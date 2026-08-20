package com.example.tools

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.example.accessibility.ZoyaAccessibilityService
import com.example.admin.NovaDeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolExecutionEngine(private val context: Context) {

    private val adminComponent = ComponentName(context, NovaDeviceAdminReceiver::class.java)
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    suspend fun execute(name: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                // --- Device Admin & Security ---
                "lockDevice" -> lockDevice()
                "isDeviceAdminActive" -> checkDeviceAdminStatus()
                "requestDeviceAdmin" -> requestDeviceAdmin()

                // --- Navigation & Accessibility Automation ---
                "clickTextOnScreen" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing 'text'"
                    val success = ZoyaAccessibilityService.clickTextOnScreen(text)
                    if (success) "Successfully clicked on '$text'." else "Failed to click on '$text'. Ensure text is visible or enable Accessibility Service."
                }
                "clickCoordinates" -> {
                    val x = args["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@withContext "Error: Missing x coordinate"
                    val y = args["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@withContext "Error: Missing y coordinate"
                    val success = ZoyaAccessibilityService.dispatchGestureClick(x, y)
                    if (success) "Tapped at coordinates ($x, $y)." else "Failed to tap coordinates."
                }
                "scrollScreen" -> {
                    val direction = args["direction"]?.jsonPrimitive?.content?.lowercase() ?: "down"
                    val displayMetrics = context.resources.displayMetrics
                    val width = displayMetrics.widthPixels.toFloat()
                    val height = displayMetrics.heightPixels.toFloat()
                    val centerX = width / 2f
                    val success = if (direction == "up") {
                        ZoyaAccessibilityService.dispatchSwipe(centerX, height * 0.3f, centerX, height * 0.8f, 300)
                    } else {
                        ZoyaAccessibilityService.dispatchSwipe(centerX, height * 0.8f, centerX, height * 0.3f, 300)
                    }
                    if (success) "Scrolled screen $direction." else "Failed to scroll screen."
                }
                "typeText" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing 'text'"
                    val success = ZoyaAccessibilityService.typeTextIntoFocusedOrFirstInput(text)
                    if (success) "Typed '$text' into the text box." else "Could not find an editable input field on screen."
                }
                "readScreenContent" -> {
                    ZoyaAccessibilityService.readAllScreenContent()
                }
                "pressBackButton" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
                        "Pressed Back button."
                    } else "Failed to press back. Accessibility service needed."
                }
                "pressHomeButton" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
                        "Returned to Home screen."
                    } else "Failed to press Home."
                }
                "openRecentApps" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) {
                        "Opened Recent Apps overview."
                    } else "Failed to open recent apps."
                }
                "openNotificationPanel" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
                        "Opened notification panel."
                    } else "Accessibility service not running. Enable NOVA in Accessibility settings."
                }
                "openQuickSettings" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
                        "Opened Quick Settings panel."
                    } else "Failed to open Quick Settings."
                }

                // --- App Launching & Web ---
                "openApp" -> {
                    val appName = args["packageName"]?.jsonPrimitive?.content ?: args["appName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing app name"
                    openAppGeneric(appName)
                }
                "searchGoogle" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing query"
                    searchGoogle(query)
                }
                "openWebUrl" -> {
                    val url = args["url"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing url"
                    openWebUrl(url)
                }
                "searchYouTube" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing query"
                    searchYouTube(query)
                }
                "playMedia" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing query"
                    playMedia(query)
                }

                // --- Communication ---
                "searchAndCallContact" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing contactName"
                    val useDialer = args["useDialer"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val simSlot = args["simSlot"]?.jsonPrimitive?.content?.toIntOrNull()
                    callContact(contactName, useDialer, simSlot)
                }
                "sendWhatsAppMessage" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing contactName"
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    sendWhatsApp(contactName, message)
                }
                "sendSMS" -> {
                    val recipient = args["recipient"]?.jsonPrimitive?.content ?: args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing recipient"
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    sendSMS(recipient, message)
                }
                "sendGmail" -> {
                    val recipient = args["recipientEmail"]?.jsonPrimitive?.content ?: ""
                    val subject = args["subject"]?.jsonPrimitive?.content ?: ""
                    val body = args["body"]?.jsonPrimitive?.content ?: ""
                    sendEmail(recipient, subject, body)
                }

                // --- Device Hardware Controls ---
                "adjustVolume" -> {
                    val direction = args["direction"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing direction (up/down/mute/unmute/max)"
                    adjustSystemVolume(direction)
                }
                "setVolumePercent" -> {
                    val percentStr = args["percent"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing percent"
                    val percent = percentStr.toIntOrNull() ?: 50
                    setVolumePercent(percent)
                }
                "toggleTorch" -> {
                    val state = args["state"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing state (on/off)"
                    toggleTorch(state)
                }
                "setBrightness" -> {
                    val levelStr = args["level"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing level"
                    val level = levelStr.toIntOrNull() ?: 50
                    setBrightness(level)
                }
                "getBatteryAndDeviceInfo" -> {
                    getBatteryAndDeviceInfo()
                }
                "getSimCardInfo" -> {
                    getSimCardInfo()
                }
                "setAlarmOrTimer" -> {
                    val type = args["type"]?.jsonPrimitive?.content ?: "alarm"
                    val hour = args["hour"]?.jsonPrimitive?.content?.toIntOrNull() ?: 7
                    val minute = args["minute"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val seconds = args["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60
                    val message = args["message"]?.jsonPrimitive?.content ?: "NOVA Reminder"
                    setAlarmOrTimer(type, hour, minute, seconds, message)
                }
                "copyToClipboard" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: ""
                    copyToClipboard(text)
                }
                "getClipboardContent" -> {
                    getClipboardContent()
                }
                else -> "Error: Tool $name not recognized."
            }
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    // --- Device Admin Methods ---
    private fun lockDevice(): String {
        return if (devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                devicePolicyManager.lockNow()
                "Device screen locked successfully."
            } catch (e: Exception) {
                "Failed to lock device: ${e.message}"
            }
        } else {
            requestDeviceAdmin()
            "Device Administrator is not active yet. I have launched the Device Admin activation screen for you. Please activate NOVA as Device Admin."
        }
    }

    private fun checkDeviceAdminStatus(): String {
        val isActive = devicePolicyManager.isAdminActive(adminComponent)
        return if (isActive) {
            "NOVA Device Administrator is ACTIVE. Screen lock and security controls are enabled."
        } else {
            "NOVA Device Administrator is INACTIVE. Run requestDeviceAdmin to activate."
        }
    }

    private fun requestDeviceAdmin(): String {
        return try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "NOVA requires Device Administrator permission to lock your screen on voice command and provide autonomous security.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening Device Admin authorization screen..."
        } catch (e: Exception) {
            "Failed to open Device Admin settings: ${e.message}"
        }
    }

    // --- App Launching ---
    private fun openAppGeneric(appName: String): String {
        val lowerName = appName.lowercase().trim()

        if (lowerName == "camera" || lowerName == "kamera") {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                "Camera opened."
            } catch (e: Exception) {
                "Failed to open camera: ${e.message}"
            }
        }

        if (lowerName == "calculator" || lowerName == "hisab") {
            val intent = Intent().apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_APP_CALCULATOR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return "Calculator opened."
            } catch (e: Exception) {}
        }

        if (lowerName == "gallery" || lowerName == "photos") {
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                type = "image/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return "Gallery opened."
            } catch (e: Exception) {}
        }

        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        
        var targetPackage: String? = null
        for (app in packages) {
            val name = pm.getApplicationLabel(app).toString().lowercase()
            if (name == lowerName || name.contains(lowerName)) {
                targetPackage = app.packageName
                break
            }
        }

        if (targetPackage == null) {
            return "Could not find an installed app matching '$appName'."
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return "App '$appName' launched."
        }
        return "Could not launch app '$appName'."
    }

    private fun searchGoogle(query: String): String {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Searching Google for '$query'..."
        } catch (e: Exception) {
            openWebUrl("https://www.google.com/search?q=${Uri.encode(query)}")
        }
    }

    private fun openWebUrl(urlStr: String): String {
        val finalUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) "https://$urlStr" else urlStr
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened URL: $finalUrl"
        } catch (e: Exception) {
            "Failed to open URL: ${e.message}"
        }
    }

    // --- Communication ---
    private fun callContact(nameOrNumber: String, useDialer: Boolean = false, simSlot: Int? = null): String {
        val isNumber = nameOrNumber.count { it.isDigit() } >= 7 || nameOrNumber.matches(Regex("^[0-9+\\-*#]+$"))
        
        val number = if (isNumber) {
            nameOrNumber.replace(Regex("[^0-9+*#]"), "")
        } else {
            val matches = findContacts(nameOrNumber)
            if (matches.isEmpty()) return "Could not find a contact matching '$nameOrNumber'. Please ask the user to verify the contact name."
            matches.first().second
        }

        val action = if (useDialer) Intent.ACTION_DIAL else Intent.ACTION_CALL
        val callIntent = Intent(action).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        if (!useDialer && simSlot != null) {
            try {
                val slotIndex = simSlot - 1
                callIntent.putExtra("com.android.phone.force.slot", true)
                callIntent.putExtra("com.android.phone.extra.slot", slotIndex)
                callIntent.putExtra("simSlot", slotIndex)
            } catch (e: Exception) {}
        }
        
        return try {
            context.startActivity(callIntent)
            if (useDialer) "Opened phone dialer with number $number." else "Initiating call to $nameOrNumber ($number)..."
        } catch (e: SecurityException) {
            "CALL_PHONE permission is required."
        }
    }

    private fun sendWhatsApp(nameOrNumber: String, message: String): String {
        val isNumber = nameOrNumber.count { it.isDigit() } >= 7 || nameOrNumber.matches(Regex("^[0-9+\\-*#]+$"))
        val number = if (isNumber) {
            nameOrNumber
        } else {
            val matches = findContacts(nameOrNumber)
            if (matches.isEmpty()) return "Could not find contact '$nameOrNumber' on device."
            matches.first().second
        }
        
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val url = "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        return try {
            ZoyaAccessibilityService.shouldAutoClick = true
            ZoyaAccessibilityService.pendingMessageToSend = message
            context.startActivity(intent)
            "Opening WhatsApp and sending message to $nameOrNumber: \"$message\""
        } catch (e: Exception) {
            ZoyaAccessibilityService.shouldAutoClick = false
            "WhatsApp is not installed on this device."
        }
    }

    private fun sendSMS(recipient: String, message: String): String {
        val isNumber = recipient.count { it.isDigit() } >= 7 || recipient.matches(Regex("^[0-9+\\-*#]+$"))
        val number = if (isNumber) {
            recipient
        } else {
            val matches = findContacts(recipient)
            if (matches.isEmpty()) return "Could not find contact '$recipient' to send SMS."
            matches.first().second
        }

        return try {
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            "SMS composer opened for $recipient ($number) with message: \"$message\""
        } catch (e: Exception) {
            "Failed to send SMS: ${e.message}"
        }
    }

    private fun sendEmail(recipient: String, subject: String, body: String): String {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:") 
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(emailIntent)
            "Email draft prepared for $recipient."
        } catch (e: Exception) {
            "No email app installed."
        }
    }

    private fun searchYouTube(query: String): String {
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Searching YouTube for '$query'..."
        } catch (e: Exception) {
            openWebUrl("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        }
    }

    private fun playMedia(query: String): String {
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Playing '$query'..."
        } catch (e: Exception) {
            searchYouTube(query)
        }
    }

    // --- System Hardware & Info ---
    private fun adjustSystemVolume(direction: String): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = AudioManager.STREAM_MUSIC
        return try {
            when (direction.lowercase()) {
                "up" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Volume increased."
                }
                "down" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume decreased."
                }
                "mute" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume muted."
                }
                "unmute" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume unmuted."
                }
                "max" -> {
                    val maxVol = audioManager.getStreamMaxVolume(streamType)
                    audioManager.setStreamVolume(streamType, maxVol, AudioManager.FLAG_SHOW_UI)
                    "Volume set to maximum."
                }
                else -> "Volume action '$direction' unknown. Options: up, down, mute, unmute, max."
            }
        } catch (e: Exception) {
            "Failed to adjust volume: ${e.message}"
        }
    }

    private fun setVolumePercent(percent: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = AudioManager.STREAM_MUSIC
        return try {
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val clamped = percent.coerceIn(0, 100)
            val targetVol = (maxVol * clamped) / 100
            audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
            "Volume set to $clamped%."
        } catch (e: Exception) {
            "Failed to set volume: ${e.message}"
        }
    }

    private fun toggleTorch(state: String): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            val enable = state.lowercase() == "on" || state.lowercase() == "true"
            cameraManager.setTorchMode(cameraId, enable)
            if (enable) "Flashlight turned ON." else "Flashlight turned OFF."
        } catch (e: Exception) {
            "Flashlight error: ${e.message}"
        }
    }

    private fun setBrightness(level: Int): String {
        return try {
            if (!Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Please grant 'Modify system settings' permission to allow NOVA to change screen brightness."
            } else {
                val brightness = (level.coerceIn(0, 100) * 255) / 100
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                "Brightness set to $level%."
            }
        } catch (e: Exception) {
            "Brightness error: ${e.message}"
        }
    }

    private fun getBatteryAndDeviceInfo(): String {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else level

            val now = java.text.SimpleDateFormat("EEEE, dd MMMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
            val adminActive = devicePolicyManager.isAdminActive(adminComponent)

            "Device Status:\n" +
            "• Battery: $batteryPct% ${if (isCharging) "(Charging ⚡)" else "(Discharging)"}\n" +
            "• Current Time: $now\n" +
            "• Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "• Device Model: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}\n" +
            "• Device Admin Status: ${if (adminActive) "Active" else "Inactive"}\n" +
            "• Accessibility Automation: ${if (ZoyaAccessibilityService.instance != null) "Connected" else "Disabled"}"
        } catch (e: Exception) {
            "Failed to retrieve device status: ${e.message}"
        }
    }

    private fun getSimCardInfo(): String {
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "READ_PHONE_STATE permission is missing. Proceeding with single SIM mode."
        }
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            "The device has ${phoneAccounts.size} active calling SIM card(s)."
        } catch (e: Exception) {
            "Error determining SIM cards: ${e.message}"
        }
    }

    private fun setAlarmOrTimer(type: String, hour: Int, minute: Int, seconds: Int, message: String): String {
        return try {
            if (type.lowercase() == "timer") {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Timer set for $seconds seconds with label '$message'."
            } else {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Alarm set for %02d:%02d with label '$message'.".format(hour, minute)
            }
        } catch (e: Exception) {
            "Failed to set alarm/timer: ${e.message}"
        }
    }

    private fun copyToClipboard(text: String): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("NOVA Clipboard", text)
            clipboard.setPrimaryClip(clip)
            "Copied to clipboard: \"$text\""
        } catch (e: Exception) {
            "Clipboard error: ${e.message}"
        }
    }

    private fun getClipboardContent(): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                "Clipboard contents: \"$text\""
            } else {
                "Clipboard is empty."
            }
        } catch (e: Exception) {
            "Failed to read clipboard: ${e.message}"
        }
    }

    // --- Contacts Finder ---
    private fun findContacts(namePattern: String): List<Pair<String, String>> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        
        try {
            val fallbackUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val fbProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(fallbackUri, fbProjection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                val exactMatches = mutableListOf<Pair<String, String>>()
                val startsWithMatches = mutableListOf<Pair<String, String>>()
                val containsMatches = mutableListOf<Pair<String, String>>()
                val fuzzyMatches = mutableListOf<Pair<Int, Pair<String, String>>>()
                
                val cleanPattern = namePattern.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                val searchWords = cleanPattern.split(" ").filter { it.isNotEmpty() }

                while (cursor.moveToNext()) {
                    val contactName = cursor.getString(nameIdx) ?: continue
                    val contactNum = cursor.getString(numIdx) ?: continue
                    
                    val cleanContactName = contactName.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                    if (cleanContactName.isEmpty()) continue

                    val contactNameNoSpace = cleanContactName.replace(" ", "")
                    val patternNoSpace = cleanPattern.replace(" ", "")

                    if (contactNameNoSpace == patternNoSpace || cleanContactName == cleanPattern) {
                        exactMatches.add(Pair(contactName, contactNum))
                    } else if (contactNameNoSpace.startsWith(patternNoSpace) || cleanContactName.startsWith(cleanPattern)) {
                        startsWithMatches.add(Pair(contactName, contactNum))
                    } else if (searchWords.isNotEmpty() && searchWords.all { cleanContactName.contains(it) }) {
                        containsMatches.add(Pair(contactName, contactNum))
                    } else if (contactNameNoSpace.contains(patternNoSpace) && patternNoSpace.length > 2) {
                        containsMatches.add(Pair(contactName, contactNum))
                    }
                    
                    val distance = levenshtein(contactNameNoSpace, patternNoSpace)
                    if (distance <= 2 && patternNoSpace.length > 3) {
                        fuzzyMatches.add(Pair(distance, Pair(contactName, contactNum)))
                    }
                }
                
                if (exactMatches.isNotEmpty()) return exactMatches.distinctBy { it.second }
                if (startsWithMatches.isNotEmpty()) return startsWithMatches.distinctBy { it.second }
                if (containsMatches.isNotEmpty()) return containsMatches.distinctBy { it.second }
                if (fuzzyMatches.isNotEmpty()) {
                    return fuzzyMatches.sortedBy { it.first }.map { it.second }.distinctBy { it.second }
                }
            }
        } catch (e: Exception) {
            Log.e("NOVA_Tools", "Error querying contacts", e)
        }
        return emptyList()
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length
        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1)

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }
}
