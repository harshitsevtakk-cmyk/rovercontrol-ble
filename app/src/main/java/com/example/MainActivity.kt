package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.*
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: RoverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

// Custom high-performance dynamic blur mesh gradient drawn programmatically
fun Modifier.meshGradientBackground(): Modifier = this.drawBehind {
    // 1. Draw solid deep space background
    drawRect(color = Color(0xFF0A051B))

    // 2. Radial gradient 1: Top-Left Deep Violet Glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF1B0B2E), Color.Transparent),
            center = Offset(size.width * 0.15f, size.height * 0.25f),
            radius = size.minDimension * 0.9f
        ),
        radius = size.minDimension * 0.9f,
        center = Offset(size.width * 0.15f, size.height * 0.25f),
        alpha = 0.65f
    )

    // 3. Radial gradient 2: Bottom-Right Electric Blue Glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF003DDF), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.85f),
            radius = size.minDimension * 0.9f
        ),
        radius = size.minDimension * 0.9f,
        center = Offset(size.width * 0.85f, size.height * 0.85f),
        alpha = 0.55f
    )

    // 4. Radial gradient 3: Center Pulsing Hot Pink Glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFDF0070), Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            radius = size.minDimension * 1.0f
        ),
        radius = size.minDimension * 1.0f,
        center = Offset(size.width * 0.5f, size.height * 0.5f),
        alpha = 0.45f
    )

    // 5. Radial gradient 4: Top-Right Vivid Cyan Glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF00C0DF), Color.Transparent),
            center = Offset(size.width * 0.8f, size.height * 0.15f),
            radius = size.minDimension * 0.8f
        ),
        radius = size.minDimension * 0.8f,
        center = Offset(size.width * 0.8f, size.height * 0.15f),
        alpha = 0.45f
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: RoverViewModel) {
    val context = LocalContext.current

    // Observe State flows
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val scannedDevices by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val temperature by viewModel.temperature.collectAsStateWithLifecycle()
    val humidity by viewModel.humidity.collectAsStateWithLifecycle()
    val lastSentBytes by viewModel.lastSentBytes.collectAsStateWithLifecycle()
    val isSimulationMode by viewModel.isSimulationMode.collectAsStateWithLifecycle()
    val servoAngles by viewModel.servoAngles.collectAsStateWithLifecycle()
    val joystickRaw by viewModel.joystickRaw.collectAsStateWithLifecycle()

    // Dialog state for device selection
    var showScanDialog by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }

    // List of permissions to request based on Android SDK level
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    // Check initial permissions
    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        permissionsGranted = allGranted
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        permissionsGranted = allGranted
        if (allGranted) {
            Toast.makeText(context, "Bluetooth Permissions Granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Scanning will be restricted to Simulated Mode.", Toast.LENGTH_LONG).show()
        }
    }

    // Background mesh drawing (using high-performance dynamic Canvas drawBehind)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .meshGradientBackground()
    ) {
        // Overlay transparent vignetted dark overlay for dramatic cinematic depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0x9005020F)),
                        center = Offset.Unspecified,
                        radius = 1200f
                    )
                )
        )

        // Main Scaffold to handle Edge-To-Edge safe padding
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val isPortrait = maxWidth < maxHeight

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. TOP CENTER TELEMETRY & STATUS CAPSULE
                    HeaderCapsule(
                        connectionState = connectionState,
                        temperature = temperature,
                        humidity = humidity,
                        isSimulationMode = isSimulationMode,
                        onToggleSimulation = { viewModel.toggleSimulationMode(it) },
                        onConnectClick = {
                            if (!permissionsGranted && !isSimulationMode) {
                                permissionLauncher.launch(requiredPermissions)
                            } else {
                                viewModel.startScanning()
                                showScanDialog = true
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. PRIMARY CONTROLS CONTAINER (Portrait-stacked, Landscape-side-by-side)
                    if (isPortrait) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LeftDrivePanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(290.dp),
                                joystickRaw = joystickRaw,
                                onJoystickMoved = { x, y -> viewModel.updateJoystickState(x, y) },
                                onJoystickReleased = { viewModel.resetJoystick() }
                            )

                            RightServoPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight(),
                                servoAngles = servoAngles,
                                isPortrait = true,
                                onServoChanged = { index, angle -> viewModel.updateServoAngle(index, angle) }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LeftDrivePanel(
                                modifier = Modifier
                                    .weight(0.42f)
                                    .fillMaxHeight(),
                                joystickRaw = joystickRaw,
                                onJoystickMoved = { x, y -> viewModel.updateJoystickState(x, y) },
                                onJoystickReleased = { viewModel.resetJoystick() }
                            )

                            RightServoPanel(
                                modifier = Modifier
                                    .weight(0.58f)
                                    .fillMaxHeight(),
                                servoAngles = servoAngles,
                                isPortrait = false,
                                onServoChanged = { index, angle -> viewModel.updateServoAngle(index, angle) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. LOW-LATENCY PACKET LOG TERMINAL
                    LivePacketTerminal(
                        lastSentBytes = lastSentBytes,
                        isSimulationMode = isSimulationMode
                    )
                }
            }
        }
    }

    // Bluetooth / Rover Scanning Dialog
    if (showScanDialog) {
        DeviceScanDialog(
            connectionState = connectionState,
            scannedDevices = scannedDevices,
            isSimulationMode = isSimulationMode,
            onDismiss = {
                viewModel.stopScanning()
                showScanDialog = false
            },
            onDeviceSelected = { deviceAddress ->
                viewModel.connectDevice(deviceAddress)
                showScanDialog = false
            },
            onRetryScan = {
                viewModel.startScanning()
            }
        )
    }
}

// ------------------------------------------------------------------------
// TOP BAR / TELEMETRY CAPSULE BAR (COMPACT & UNCLUTTERED PILL)
// ------------------------------------------------------------------------
@Composable
fun HeaderCapsule(
    connectionState: BleConnectionState,
    temperature: Int,
    humidity: Int,
    isSimulationMode: Boolean,
    onToggleSimulation: (Boolean) -> Unit,
    onConnectClick: () -> Unit
) {
    // Pulse animation for scanner / disconnected state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Capsule border & glass glow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(SurfaceGlass)
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x05FFFFFF)))
                ),
                RoundedCornerShape(29.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // BLE Connection State
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .clickable { onConnectClick() }
                .background(SurfaceGlassAccent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val statusColor = when (connectionState) {
                BleConnectionState.CONNECTED -> GlowingGreen
                BleConnectionState.SCANNING -> PulsingAmber
                BleConnectionState.CONNECTING -> PulsingAmber
                BleConnectionState.DISCONNECTED -> DangerRed
            }

            val statusText = when (connectionState) {
                BleConnectionState.CONNECTED -> "CONNECTED"
                BleConnectionState.SCANNING -> "SCANNING"
                BleConnectionState.CONNECTING -> "CONNECTING"
                BleConnectionState.DISCONNECTED -> "OFFLINE"
            }

            val alphaVal = if (connectionState == BleConnectionState.SCANNING || connectionState == BleConnectionState.CONNECTING) {
                pulseAlpha
            } else {
                1.0f
            }

            // Glowing Indicator Dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .drawBehind {
                        drawCircle(
                            color = statusColor.copy(alpha = alphaVal * 0.4f),
                            radius = size.width * 1.5f
                        )
                        drawCircle(
                            color = statusColor.copy(alpha = alphaVal),
                            radius = size.width * 0.5f
                        )
                    }
            )

            Text(
                text = statusText,
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Live DHT11 Sensor Readouts
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Temperature Display
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ThermometerIcon(modifier = Modifier.size(14.dp))
                Text(
                    text = if (temperature > 0) "$temperature°C" else "--°C",
                    fontSize = 11.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Divider vertical
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(Color(0x33FFFFFF))
            )

            // Humidity Display
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HumidityIcon(modifier = Modifier.size(14.dp))
                Text(
                    text = if (humidity > 0) "$humidity%" else "--%",
                    fontSize = 11.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Space-Saving, Futuristic Simulator Chip (Replaces heavy Switch)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (isSimulationMode) Color(0x2600D2FF) else Color(0x0CFFFFFF))
                .border(
                    BorderStroke(1.dp, if (isSimulationMode) Color(0x8000D2FF) else Color(0x1FFFFFFF)),
                    RoundedCornerShape(14.dp)
                )
                .clickable { onToggleSimulation(!isSimulationMode) }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("simulator_toggle")
        ) {
            Text(
                text = if (isSimulationMode) "SIMULATOR" else "HARDWARE",
                color = if (isSimulationMode) NeonBlue else TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}



// ------------------------------------------------------------------------
// LEFT PANEL - JOYSTICK DRIVE CONTROLLER
// ------------------------------------------------------------------------
@Composable
fun LeftDrivePanel(
    modifier: Modifier = Modifier,
    joystickRaw: Pair<Int, Int>,
    onJoystickMoved: (Float, Float) -> Unit,
    onJoystickReleased: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceGlass)
            .border(
                BorderStroke(1.dp, GlassBorder),
                RoundedCornerShape(24.dp)
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DRIVE SYSTEMS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "L298N DUAL H-BRIDGE",
                        fontSize = 8.sp,
                        color = NeonBlue,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }

                // Mini stats badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceGlassAccent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "RAW X:${joystickRaw.first} Y:${joystickRaw.second}",
                        color = NeonBlue,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Custom Joystick Composable
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Joystick(
                    onJoystickMoved = onJoystickMoved,
                    onJoystickReleased = onJoystickReleased
                )
            }

            // Command output details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Motor Indicator
                MotorSpeedIndicator(
                    label = "LEFT MOTOR",
                    value = calculateLeftMotor(joystickRaw.first, joystickRaw.second)
                )

                // Right Motor Indicator
                MotorSpeedIndicator(
                    label = "RIGHT MOTOR",
                    value = calculateRightMotor(joystickRaw.first, joystickRaw.second)
                )
            }
        }
    }
}

// Basic Differential Drive Calculation for simple visualization
fun calculateLeftMotor(x: Int, y: Int): Int {
    val speed = y - 127
    val steering = x - 127
    return (speed + steering).coerceIn(-127, 127)
}

fun calculateRightMotor(x: Int, y: Int): Int {
    val speed = y - 127
    val steering = x - 127
    return (speed - steering).coerceIn(-127, 127)
}

@Composable
fun MotorSpeedIndicator(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val isForward = value >= 0
            val absVal = java.lang.Math.abs(value)
            val percent = (absVal * 100) / 127

            Text(
                text = "${if (isForward) "FWD" else "REV"} $percent%",
                color = if (value == 0) TextMuted else if (isForward) GlowingGreen else HotMagenta,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ------------------------------------------------------------------------
// THE CUSTOM JOYSTICK
// ------------------------------------------------------------------------
@Composable
fun Joystick(
    modifier: Modifier = Modifier,
    onJoystickMoved: (xPercent: Float, yPercent: Float) -> Unit,
    onJoystickReleased: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadiusDp = 64.dp
    val density = LocalDensity.current
    val maxRadiusPx = with(density) { maxRadiusDp.toPx() }

    // Spring animation to return thumb to center when released
    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffset.x,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "joystickX"
    )
    val animatedOffsetY by animateFloatAsState(
        targetValue = dragOffset.y,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "joystickY"
    )

    Box(
        modifier = modifier
            .size(maxRadiusDp * 2.3f)
            .drawBehind {
                val centerPt = center
                val outerRadius = size.width / 2

                // Outer Glass Ring with Glowing Border
                drawCircle(
                    color = Color(0x0C80DEEA),
                    radius = outerRadius,
                    center = centerPt
                )
                drawCircle(
                    color = GlassBorder,
                    radius = outerRadius,
                    center = centerPt,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Subdued crosshairs
                drawLine(
                    color = Color(0x1AFFFFFF),
                    start = Offset(0f, centerPt.y),
                    end = Offset(size.width, centerPt.y),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color(0x1AFFFFFF),
                    start = Offset(centerPt.x, 0f),
                    end = Offset(centerPt.x, size.height),
                    strokeWidth = 1.dp.toPx()
                )

                // Guide concentric circles
                drawCircle(
                    color = Color(0x08FFFFFF),
                    radius = outerRadius * 0.6f,
                    center = centerPt,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {},
                    onDragEnd = {
                        dragOffset = Offset.Zero
                        onJoystickReleased()
                    },
                    onDragCancel = {
                        dragOffset = Offset.Zero
                        onJoystickReleased()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val rawOffset = dragOffset + dragAmount
                        val distance = rawOffset.getDistance()

                        dragOffset = if (distance <= maxRadiusPx) {
                            rawOffset
                        } else {
                            rawOffset * (maxRadiusPx / distance)
                        }

                        // Coordinates normalized between -1.0 and 1.0
                        onJoystickMoved(
                            dragOffset.x / maxRadiusPx,
                            dragOffset.y / maxRadiusPx
                        )
                    }
                )
            }
    ) {
        // Joystick Thumb: glossy liquid-filled orb thumb
        val thumbSize = 52.dp
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        animatedOffsetX.toInt(),
                        animatedOffsetY.toInt()
                    )
                }
                .align(Alignment.Center)
                .size(thumbSize)
                .clip(CircleShape)
                .drawBehind {
                    val r = size.width / 2
                    val centerPt = Offset(r, r)

                    // 1. Radial gradient for deep 3D glossy orb feel
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                HotMagenta,
                                TechPurple,
                                Color(0xFF150A2E)
                            ),
                            center = centerPt - Offset(r * 0.35f, r * 0.35f),
                            radius = r * 1.5f
                        ),
                        radius = r,
                        center = centerPt
                    )

                    // 2. Shiny liquid glare highlighting (frosted glass/liquid top sheen)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.85f),
                                Color.White.copy(alpha = 0.1f),
                                Color.Transparent
                            ),
                            center = centerPt - Offset(r * 0.4f, r * 0.4f),
                            radius = r * 0.55f
                        ),
                        radius = r * 0.55f,
                        center = centerPt - Offset(r * 0.4f, r * 0.4f)
                    )

                    // 3. Highlight Crescent
                    drawCircle(
                        color = Color.White.copy(alpha = 0.4f),
                        radius = r - 1.5.dp.toPx(),
                        center = centerPt,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
        )
    }
}

// ------------------------------------------------------------------------
// RIGHT PANEL - 6 SERVO ROBOTIC CONTROLLER
// ------------------------------------------------------------------------
@Composable
fun RightServoPanel(
    modifier: Modifier = Modifier,
    servoAngles: List<Int>,
    isPortrait: Boolean,
    onServoChanged: (Int, Int) -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceGlass)
            .border(
                BorderStroke(1.dp, GlassBorder),
                RoundedCornerShape(24.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = if (isPortrait) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SERVO MOTOR CONTROL",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "PCA9685 16-CHANNEL PWM DRIVER",
                        fontSize = 8.sp,
                        color = TechPurple,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }

                // Info Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceGlassAccent)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "I2C ADDR: 0x40",
                        color = TechPurple,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Grid of 6 Servos (2 Columns, 3 Rows)
            Column(
                modifier = if (isPortrait) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (row in 0..2) {
                    Row(
                        modifier = if (isPortrait) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        for (col in 0..1) {
                            val servoIndex = row * 2 + col
                            ServoSliderCell(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (isPortrait) 68.dp else 56.dp),
                                index = servoIndex,
                                angle = servoAngles.getOrElse(servoIndex) { 90 },
                                isPortrait = isPortrait,
                                onAngleChanged = { angle -> onServoChanged(servoIndex, angle) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ServoSliderCell(
    modifier: Modifier = Modifier,
    index: Int,
    angle: Int,
    isPortrait: Boolean,
    onAngleChanged: (Int) -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x0CFFFFFF))
            .border(BorderStroke(1.dp, Color(0x17FFFFFF)), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = if (isPortrait) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Label and Angle Display Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SERVO ${index + 1}",
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                // Glowing Badge showing angle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceGlassAccent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$angle°",
                        color = NeonBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // High precision volume-style slider
            Slider(
                value = angle.toFloat(),
                onValueChange = { onAngleChanged(it.toInt()) },
                valueRange = 0f..180f,
                colors = SliderDefaults.colors(
                    thumbColor = NeonBlue,
                    activeTrackColor = TechPurple,
                    inactiveTrackColor = Color(0x1FDFDFDF),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .testTag("servo_slider_$index")
            )
        }
    }
}

// ------------------------------------------------------------------------
// REAL-TIME LOW-LATENCY PACKET LOG TERMINAL
// ------------------------------------------------------------------------
@Composable
fun LivePacketTerminal(
    lastSentBytes: ByteArray?,
    isSimulationMode: Boolean
) {
    // Beautiful mini dark glass console showing high-speed byte streams
    Box(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE605030A))
            .border(BorderStroke(1.dp, Color(0x1F80DEEA)), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left details
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x3300E676))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "TX 25ms",
                        color = GlowingGreen,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = "PACKET STREAM (9 BYTES):",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            // Live byte rendering
            val byteString = remember(lastSentBytes) {
                if (lastSentBytes == null || lastSentBytes.isEmpty()) {
                    "WAITING FOR PACKETS..."
                } else {
                    lastSentBytes.joinToString(separator = " ") { byte ->
                        "0x%02X".format(byte)
                    }
                }
            }

            Text(
                text = byteString,
                color = NeonBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )

            // Sync Header Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(SurfaceGlassAccent)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (lastSentBytes != null && lastSentBytes.isNotEmpty()) "SYNC OK" else "STANDBY",
                    color = if (lastSentBytes != null && lastSentBytes.isNotEmpty()) GlowingGreen else PulsingAmber,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ------------------------------------------------------------------------
// DEVICE DISCOVERY SCAN DIALOG
// ------------------------------------------------------------------------
@Composable
fun DeviceScanDialog(
    connectionState: BleConnectionState,
    scannedDevices: List<ScannedDevice>,
    isSimulationMode: Boolean,
    onDismiss: () -> Unit,
    onDeviceSelected: (String) -> Unit,
    onRetryScan: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFA15102F)) // deep dark solid glass
                .border(BorderStroke(1.5.dp, TechPurple.copy(alpha = 0.5f)), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DISCOVER ESP32 ROVERS",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (isSimulationMode) "Simulated Rover Radar Scan" else "Bluetooth Low Energy Scans",
                            color = TextMuted,
                            fontSize = 9.sp
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Canvas(modifier = Modifier.size(16.dp)) {
                            drawLine(
                                color = TextMuted,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = TextMuted,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, 0f),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable device list
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33000000))
                        .padding(4.dp)
                ) {
                    if (scannedDevices.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = TechPurple,
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "SCANNING FOR ROVERS...",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(scannedDevices) { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x12FFFFFF))
                                        .clickable { onDeviceSelected(device.address) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = device.name,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = device.address,
                                            color = TextMuted,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Signal badge
                                        Text(
                                            text = "${device.rssi} dBm",
                                            color = NeonBlue,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )

                                        // Connect arrow icon custom drawn
                                        Canvas(modifier = Modifier.size(10.dp)) {
                                            val w = size.width
                                            val h = size.height
                                            val path = Path().apply {
                                                moveTo(0f, h * 0.5f)
                                                lineTo(w, h * 0.5f)
                                                moveTo(w * 0.6f, h * 0.1f)
                                                lineTo(w, h * 0.5f)
                                                lineTo(w * 0.6f, h * 0.9f)
                                            }
                                            drawPath(
                                                path = path,
                                                color = TechPurple,
                                                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onRetryScan,
                        colors = ButtonDefaults.textButtonColors(contentColor = NeonBlue)
                    ) {
                        Text(
                            text = "RESCAN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------
// CUSTOM PROGRAMMATIC VECTOR DRAWINGS
// ------------------------------------------------------------------------
@Composable
fun ThermometerIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stemWidth = w * 0.22f
        val bulbRadius = w * 0.25f

        // Draw outer glass thermometer frame
        val glassPath = Path().apply {
            // Start top rounded cap
            moveTo(w * 0.5f - stemWidth / 2, h * 0.15f)
            // Left side down
            lineTo(w * 0.5f - stemWidth / 2, h * 0.55f)
            // Bulge for bulb
            val bulbCenter = Offset(w * 0.5f, h * 0.72f)
            // Draw arc around bulb
            arcTo(
                rect = Size(bulbRadius * 2, bulbRadius * 2).let { sz ->
                    androidx.compose.ui.geometry.Rect(
                        bulbCenter.x - bulbRadius,
                        bulbCenter.y - bulbRadius,
                        bulbCenter.x + bulbRadius,
                        bulbCenter.y + bulbRadius
                    )
                },
                startAngleDegrees = 120f,
                sweepAngleDegrees = 300f,
                forceMoveTo = false
            )
            // Right side up
            lineTo(w * 0.5f + stemWidth / 2, h * 0.15f)
            // Close with rounded arc
            arcTo(
                rect = Size(stemWidth, stemWidth).let { sz ->
                    androidx.compose.ui.geometry.Rect(
                        w * 0.5f - stemWidth / 2,
                        h * 0.15f - stemWidth / 2,
                        w * 0.5f + stemWidth / 2,
                        h * 0.15f + stemWidth / 2
                    )
                },
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
        }

        drawPath(
            path = glassPath,
            color = Color.White.copy(alpha = 0.3f),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Draw hot magenta mercury inside
        val mercuryBulbRadius = bulbRadius * 0.65f
        val mercuryBulbCenter = Offset(w * 0.5f, h * 0.72f)
        drawCircle(
            color = HotMagenta,
            radius = mercuryBulbRadius,
            center = mercuryBulbCenter
        )

        // Mercury column in stem
        drawRoundRect(
            color = HotMagenta,
            topLeft = Offset(w * 0.5f - stemWidth * 0.3f, h * 0.35f),
            size = Size(stemWidth * 0.6f, h * 0.35f),
            cornerRadius = CornerRadius(stemWidth * 0.3f, stemWidth * 0.3f)
        )

        // Specular reflection shine
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = mercuryBulbRadius * 0.3f,
            center = mercuryBulbCenter - Offset(mercuryBulbRadius * 0.3f, mercuryBulbRadius * 0.3f)
        )
    }
}

@Composable
fun HumidityIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            cubicTo(
                w * 0.15f, h * 0.52f,
                w * 0.15f, h * 0.88f,
                w * 0.5f, h * 0.88f
            )
            cubicTo(
                w * 0.85f, h * 0.88f,
                w * 0.85f, h * 0.52f,
                w * 0.5f, h * 0.12f
            )
            close()
        }

        drawPath(
            path = path,
            color = NeonBlue
        )

        // Droplet specular high glow
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = w * 0.08f,
            center = Offset(w * 0.4f, h * 0.65f)
        )
    }
}

@Composable
fun BluetoothIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(w * 0.32f, h * 0.28f)
            lineTo(w * 0.68f, h * 0.68f)
            lineTo(w * 0.5f, h * 0.88f)
            lineTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.68f, h * 0.32f)
            lineTo(w * 0.32f, h * 0.72f)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

// Retained for screenshot test compatibility
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

