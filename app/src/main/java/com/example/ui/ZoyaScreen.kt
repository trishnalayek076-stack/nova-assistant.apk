package com.example.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.BuildConfig
import com.example.ZoyaForegroundService
import com.example.accessibility.ZoyaAccessibilityService
import com.example.admin.NovaDeviceAdminReceiver
import com.example.live.ZoyaState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun ZoyaScreen() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToChat = { navController.navigate("chat") }
            )
        }
        composable("chat") {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigateToChat: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("NovaPrefs", Context.MODE_PRIVATE) }
    var apiKey by remember {
        mutableStateOf(
            prefs.getString("api_key", "")?.takeIf { it.isNotBlank() }
                ?: context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE).getString("api_key", "")?.takeIf { it.isNotBlank() }
                ?: BuildConfig.GEMINI_API_KEY
        )
    }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var zoyaState by remember { mutableStateOf(ZoyaForegroundService.currentState) }
    var serviceStarted by remember { mutableStateOf(ZoyaForegroundService.activeService != null) }
    var showMenu by remember { mutableStateOf(false) }

    val adminComponent = remember { ComponentName(context, NovaDeviceAdminReceiver::class.java) }
    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    var isAdminActive by remember { mutableStateOf(dpm.isAdminActive(adminComponent)) }
    var isAccessibilityActive by remember { mutableStateOf(ZoyaAccessibilityService.instance != null) }

    val audioAmplitude by ZoyaForegroundService.audioAmplitude.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasMic = permissions[android.Manifest.permission.RECORD_AUDIO] == true
        if (hasMic) {
            val intent = Intent(context, ZoyaForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
            serviceStarted = true
        } else {
            android.widget.Toast.makeText(context, "Microphone permission is required for NOVA!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        ZoyaForegroundService.onStateChange = { state ->
            zoyaState = state
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            isAdminActive = dpm.isAdminActive(adminComponent)
            isAccessibilityActive = ZoyaAccessibilityService.instance != null
            serviceStarted = ZoyaForegroundService.activeService != null
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "N.O.V.A.",
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 24.sp,
                                letterSpacing = 3.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "AI BUTLER",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            "Autonomous Voice & OS Agent",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(
                        onClick = onNavigateToChat,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .testTag("chat_logs_button")
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs", tint = Color(0xFF00E5FF))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { showMenu = !showMenu },
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .testTag("settings_menu_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier
                            .background(Color(0xFF13172A))
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Activity & Command Log", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.List, contentDescription = null, tint = Color(0xFF00E5FF)) },
                            onClick = {
                                showMenu = false
                                onNavigateToChat()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Gemini API Key", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFFFFD54F)) },
                            onClick = {
                                showMenu = false
                                showApiKeyDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("System Permissions", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF00E676)) },
                            onClick = {
                                showMenu = false
                                showPermissionsDialog = true
                            }
                        )
                    }
                }
            )
        },
        containerColor = Color(0xFF0A0C16)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF151932), Color(0xFF070913)),
                        radius = 1600f
                    )
                )
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Status Badges Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatusPill(
                        label = "Device Admin",
                        isActive = isAdminActive,
                        activeColor = Color(0xFF00E676),
                        inactiveColor = Color(0xFFFF5252),
                        onClick = {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Enables NOVA to lock device and automate security policies."
                                )
                            }
                            context.startActivity(intent)
                        }
                    )

                    StatusPill(
                        label = "Accessibility",
                        isActive = isAccessibilityActive,
                        activeColor = Color(0xFF00E5FF),
                        inactiveColor = Color(0xFFFF9100),
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Reactive Glowing NOVA Orb with live microphone visualizer
                NovaOrb(state = zoyaState, amplitude = audioAmplitude)

                Spacer(modifier = Modifier.height(28.dp))

                // Live Assistant State Indicator
                val stateText = when (zoyaState) {
                    ZoyaState.LISTENING -> "Listening & Ready..."
                    ZoyaState.THINKING -> "Processing Action..."
                    ZoyaState.SPEAKING -> "NOVA Speaking..."
                    ZoyaState.IDLE -> if (serviceStarted) "Standby Mode" else "NOVA Offline"
                }
                val stateColor by animateColorAsState(
                    targetValue = when (zoyaState) {
                        ZoyaState.LISTENING -> Color(0xFF00E5FF)
                        ZoyaState.THINKING -> Color(0xFFFF9100)
                        ZoyaState.SPEAKING -> Color(0xFF00E676)
                        ZoyaState.IDLE -> Color.White.copy(alpha = 0.5f)
                    },
                    label = "stateColor"
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(stateColor.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                        .border(1.dp, stateColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(stateColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stateText,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Quick Action Controls Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(24.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!serviceStarted) {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("start_nova_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                val hasMic = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasContacts = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasPhone = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasSms = ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (hasMic && hasContacts && hasPhone && hasSms) {
                                    val intent = Intent(context, ZoyaForegroundService::class.java)
                                    ContextCompat.startForegroundService(context, intent)
                                    serviceStarted = true
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.RECORD_AUDIO,
                                            android.Manifest.permission.READ_CONTACTS,
                                            android.Manifest.permission.CALL_PHONE,
                                            android.Manifest.permission.SEND_SMS
                                        )
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "START NOVA ASSISTANT",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                onClick = {
                                    ZoyaForegroundService.activeService?.reconnectSession()
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reconnect", color = Color(0xFF00E5FF), fontSize = 14.sp)
                            }

                            Button(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF5252).copy(alpha = 0.2f),
                                    contentColor = Color(0xFFFF8A80)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(14.dp),
                                onClick = {
                                    val intent = Intent(context, ZoyaForegroundService::class.java)
                                    context.stopService(intent)
                                    serviceStarted = false
                                }
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop NOVA", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // API Key Dialog
    if (showApiKeyDialog) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            containerColor = Color(0xFF151932),
            title = { Text("Gemini API Key Setup", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Enter your Google Gemini API key to activate real-time Live voice intelligence with NOVA.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        placeholder = { Text("AIzaSy...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Get free API Key from Google AI Studio",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
                            context.startActivity(intent)
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                    onClick = {
                        prefs.edit().putString("api_key", tempKey.trim()).apply()
                        context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE).edit().putString("api_key", tempKey.trim()).apply()
                        apiKey = tempKey.trim()
                        showApiKeyDialog = false
                    }
                ) {
                    Text("Save Key", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    // Permissions Dialog
    if (showPermissionsDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionsDialog = false },
            containerColor = Color(0xFF151932),
            title = { Text("NOVA System Permissions", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Configure full automation permissions for NOVA:", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)

                    PermissionItemRow(
                        title = "Device Administrator",
                        desc = "Lock screen on command",
                        isGranted = isAdminActive,
                        onEnable = {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enables NOVA to lock device.")
                            }
                            context.startActivity(intent)
                        }
                    )

                    PermissionItemRow(
                        title = "Accessibility Service",
                        desc = "Autonomous taps, typing & screen reading",
                        isGranted = isAccessibilityActive,
                        onEnable = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    )

                    PermissionItemRow(
                        title = "System Settings (Write)",
                        desc = "Adjust screen brightness",
                        isGranted = Settings.System.canWrite(context),
                        onEnable = {
                            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                    onClick = { showPermissionsDialog = false }
                ) {
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun StatusPill(label: String, isActive: Boolean, activeColor: Color, inactiveColor: Color, onClick: () -> Unit) {
    val color = if (isActive) activeColor else inactiveColor
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$label: ${if (isActive) "ON" else "OFF"}",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun PermissionItemRow(title: String, desc: String, isGranted: Boolean, onEnable: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(desc, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        if (isGranted) {
            Text("Active ✓", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else {
            Button(
                onClick = onEnable,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Enable", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun NovaOrb(state: ZoyaState, amplitude: Float = 0f) {
    val radiusScale = remember { Animatable(1f) }
    val glowAlpha = remember { Animatable(0.5f) }
    val ring1Angle = remember { Animatable(0f) }
    val ring2Angle = remember { Animatable(120f) }
    val ring3Angle = remember { Animatable(240f) }

    val ampBoost = (amplitude * 0.4f).coerceIn(0f, 0.5f)

    LaunchedEffect(state) {
        when (state) {
            ZoyaState.IDLE -> {
                radiusScale.animateTo(1f, animationSpec = tween(600))
                glowAlpha.animateTo(
                    targetValue = 0.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            ZoyaState.LISTENING -> {
                radiusScale.animateTo(1.1f, animationSpec = tween(400))
                glowAlpha.animateTo(
                    targetValue = 0.85f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            ZoyaState.THINKING -> {
                radiusScale.animateTo(1.05f, animationSpec = tween(300))
                glowAlpha.animateTo(
                    targetValue = 0.7f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            ZoyaState.SPEAKING -> {
                radiusScale.animateTo(1.22f, animationSpec = tween(200))
                glowAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(300, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        launch {
            ring1Angle.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(5000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            ring2Angle.animateTo(
                targetValue = 360f + 120f,
                animationSpec = infiniteRepeatable(
                    animation = tween(6500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            ring3Angle.animateTo(
                targetValue = -360f + 240f,
                animationSpec = infiniteRepeatable(
                    animation = tween(6000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
    }

    Box(
        modifier = Modifier.size(280.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseRadius = size.minDimension / 4.2f
            val currentRadius = baseRadius * (radiusScale.value + ampBoost)

            val coreInnerColor = when (state) {
                ZoyaState.IDLE -> Color(0xFF00E5FF)
                ZoyaState.LISTENING -> Color(0xFF7C4DFF)
                ZoyaState.THINKING -> Color(0xFFFFAB00)
                ZoyaState.SPEAKING -> Color(0xFF00E676)
            }

            val coreOuterColor = when (state) {
                ZoyaState.IDLE -> Color(0xFF0091EA)
                ZoyaState.LISTENING -> Color(0xFF651FFF)
                ZoyaState.THINKING -> Color(0xFFFF6D00)
                ZoyaState.SPEAKING -> Color(0xFF00B0FF)
            }

            // 1. Ambient Glow Field
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreInnerColor.copy(alpha = glowAlpha.value * 0.45f), Color.Transparent),
                    center = center,
                    radius = currentRadius * 2.8f
                ),
                radius = currentRadius * 2.8f
            )

            // 2. Futuristic Core Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f),
                        coreInnerColor.copy(alpha = 0.85f),
                        coreOuterColor.copy(alpha = 0.95f),
                        Color(0xFF0A0C16)
                    ),
                    center = Offset(center.x - currentRadius * 0.25f, center.y - currentRadius * 0.25f),
                    radius = currentRadius * 1.3f
                ),
                radius = currentRadius
            )

            // Specular Reflection
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                center = Offset(center.x - currentRadius * 0.35f, center.y - currentRadius * 0.35f),
                radius = currentRadius * 0.28f
            )

            // 3. Neon Orbitals
            val ringRadiusX = currentRadius * 1.85f
            val ringRadiusY = currentRadius * 0.65f

            fun drawNeonRing(angle: Float, startColor: Color, endColor: Color, strokeWidth: Float) {
                rotate(angle, center) {
                    drawOval(
                        brush = Brush.sweepGradient(
                            colors = listOf(startColor, endColor, startColor, Color.Transparent, startColor),
                            center = center
                        ),
                        topLeft = Offset(center.x - ringRadiusX, center.y - ringRadiusY),
                        size = Size(ringRadiusX * 2, ringRadiusY * 2),
                        style = Stroke(width = strokeWidth)
                    )
                    drawOval(
                        color = startColor.copy(alpha = 0.25f),
                        topLeft = Offset(center.x - ringRadiusX, center.y - ringRadiusY),
                        size = Size(ringRadiusX * 2, ringRadiusY * 2),
                        style = Stroke(width = strokeWidth * 2.5f)
                    )
                }
            }

            val speedMultiplier = if (state == ZoyaState.THINKING || state == ZoyaState.SPEAKING) 2f else 1f
            drawNeonRing(ring1Angle.value * speedMultiplier, Color(0xFF00E5FF), Color(0xFF0091EA), 3.5f)
            drawNeonRing(ring2Angle.value * speedMultiplier, Color(0xFFFF4081), Color(0xFFD50000), 3.5f)
            drawNeonRing(ring3Angle.value * speedMultiplier, Color(0xFF00E676), Color(0xFF76FF03), 3.5f)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onNavigateBack: () -> Unit) {
    val liveSessionManager = ZoyaForegroundService.activeService?.liveSessionManager
    val messages by (liveSessionManager?.messages ?: remember { MutableStateFlow(emptyList<String>()) }).collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0C16),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NOVA Live Console", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Status: ${ZoyaForegroundService.currentState.name}",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { ZoyaForegroundService.activeService?.reconnectSession() }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect", tint = Color(0xFF00E5FF))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF13172A))
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No activity yet. Speak to NOVA or type a command below.",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                items(messages) { msg ->
                    val isUser = msg.startsWith("You:")
                    val isNova = msg.startsWith("NOVA:")
                    val isAction = msg.startsWith("Executing:") || msg.startsWith("Result:")

                    val bgColor = when {
                        isUser -> Color(0xFF00E5FF).copy(alpha = 0.2f)
                        isNova -> Color(0xFF00E676).copy(alpha = 0.15f)
                        isAction -> Color(0xFFFF9100).copy(alpha = 0.15f)
                        else -> Color.White.copy(alpha = 0.08f)
                    }

                    val borderColor = when {
                        isUser -> Color(0xFF00E5FF).copy(alpha = 0.4f)
                        isNova -> Color(0xFF00E676).copy(alpha = 0.4f)
                        isAction -> Color(0xFFFF9100).copy(alpha = 0.4f)
                        else -> Color.White.copy(alpha = 0.15f)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isUser) 16.dp else 4.dp),
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Text(
                            text = msg,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .background(bgColor, RoundedCornerShape(14.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Text Command Input Field
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Type voice command for NOVA...", color = Color.Gray, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            liveSessionManager?.sendTextMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF00E5FF), CircleShape)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.Black)
                }
            }
        }
    }
}
