package com.robobionics.gaitimudemo
import com.robobionics.gaitimudemo.data.GaitFirebaseRepository

import android.util.Log
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robobionics.gaitimudemo.ui.theme.GaitIMUDemoTheme
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private const val USB_PERMISSION_ACTION =
    "com.robobionics.gaitimudemo.USB_PERMISSION"
private const val TAG = "GaitDebug"
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GaitIMUDemoTheme {
                GaitIMUScreen()
            }
        }
    }
}

@Composable
fun GaitIMUScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val firebaseRepository = remember {
        GaitFirebaseRepository()
    }

    var uploadStatus by remember {
        mutableStateOf("Not uploaded")
    }

    var connectionStatus by remember { mutableStateOf("Not connected") }
    var recordingStatus by remember { mutableStateOf("Not recording") }

    var ax by remember { mutableStateOf("--") }
    var ay by remember { mutableStateOf("--") }
    var az by remember { mutableStateOf("--") }
    var gx by remember { mutableStateOf("--") }
    var gy by remember { mutableStateOf("--") }
    var gz by remember { mutableStateOf("--") }
    var timestamp by remember { mutableStateOf("--") }

    var mmg1 by remember { mutableStateOf("--") }
    var mmg2 by remember { mutableStateOf("--") }
    var mmg3 by remember { mutableStateOf("--") }

    var serialPort by remember { mutableStateOf<UsbSerialPort?>(null) }
    var usbConnection by remember {
        mutableStateOf<UsbDeviceConnection?>(null)
    }
    var readJob by remember { mutableStateOf<Job?>(null) }

    var recording by remember { mutableStateOf(false) }
    var patientName by remember { mutableStateOf("") }
    var csvFile by remember { mutableStateOf<File?>(null) }

    var calibrationStatus by remember {
        mutableStateOf("Waiting for Arduino...")
    }

    var calibrationStarted by remember {
        mutableStateOf(false)
    }

    var calibrationRemaining by remember {
        mutableIntStateOf(10)
    }

    var calibrationReady by remember {
        mutableStateOf(false)
    }

    var calibrationError by remember {
        mutableStateOf(false)
    }

    var roll by remember { mutableStateOf("--") }
    var pitch by remember { mutableStateOf("--") }
    var yaw by remember { mutableStateOf("--") }

    var q0 by remember { mutableStateOf(1.0) }
    var q1 by remember { mutableStateOf(0.0) }
    var q2 by remember { mutableStateOf(0.0) }
    var q3 by remember { mutableStateOf(0.0) }

    var flexDeg by remember { mutableStateOf("--") }
    var tiltDeg by remember { mutableStateOf("--") }

    var accUsed by remember { mutableStateOf("--") }
    var stationary by remember { mutableStateOf("--") }

    fun clearSensorValues() {
        ax = "--"
        ay = "--"
        az = "--"

        gx = "--"
        gy = "--"
        gz = "--"

        mmg1 = "--"
        mmg2 = "--"
        mmg3 = "--"

        roll = "--"
        pitch = "--"
        yaw = "--"

        timestamp = "--"

        flexDeg = "--"
        tiltDeg = "--"
        accUsed = "--"
        stationary = "--"
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val file = csvFile
                    if (file == null || !file.exists()) {
                        recordingStatus = "CSV file not found"
                    } else {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                file.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            } ?: throw Exception("Could not open destination")
                        }
                        recordingStatus = "CSV exported successfully"
                    }
                } catch (e: Exception) {
                    recordingStatus = "Export failed: ${e.message}"
                }
            }
        }
    }

    var fileOutput by remember { mutableStateOf<FileOutputStream?>(null) }

    // Arduino format:
// DATA,time_ms,ax,ay,az,gx,gy,gz,mmg1,mmg2,mmg3
    val processLine: (String) -> Unit = { line ->

        Log.d(TAG, "Received from Arduino: $line")

        val parts = line.trim().split(",")

        // =============================================
        // 1. HANDLE STATUS MESSAGES
        // =============================================

        if (parts.isNotEmpty() && parts[0] == "STATUS") {

            Log.d(TAG, "Status message received: ${parts.joinToString(",")}")

            when (parts.getOrNull(1)) {

                "CALIBRATION_STARTED" -> {

                    calibrationStarted = true
                    calibrationReady = false
                    calibrationError = false
                    calibrationRemaining = 10

                    calibrationStatus = "Calibration started"
                }

                "CALIBRATION" -> {

                    val remaining =
                        parts.getOrNull(2)?.toIntOrNull()

                    if (remaining != null) {

                        calibrationStarted = true
                        calibrationRemaining = remaining
                        calibrationReady = false

                        calibrationStatus = "Calibrating..."
                    }
                }

                "CALIBRATION_COMPLETE" -> {

                    calibrationStarted = false
                    calibrationRemaining = 0
                    calibrationReady = true
                    calibrationError = false

                    calibrationStatus =
                        "Calibration Complete — Ready to Record"
                }

                "CALIBRATION_ERROR" -> {

                    calibrationStarted = false
                    calibrationReady = false
                    calibrationError = true

                    calibrationStatus =
                        "Calibration failed. Restart the device."
                }

                "IMU_ERROR" -> {

                    calibrationStarted = false
                    calibrationReady = false
                    calibrationError = true

                    calibrationStatus =
                        "IMU initialization failed"
                }
            }
        }

        // =============================================
        // 2. HANDLE SENSOR DATA
        // =============================================

        // Expected format:
        // DATA,time_ms,ax,ay,az,gx,gy,gz,mmg1,mmg2,mmg3


// =============================================
// 2. HANDLE SENSOR DATA
// =============================================

// Expected format:
// DATA,time_ms,ax,ay,az,gx,gy,gz,
// q0,q1,q2,q3,flex_deg,tilt_deg,
// acc_used,stationary,mmg1,mmg2,mmg3


        if (parts.size == 18 && parts[0].trim() == "DATA") {

            val values = parts.drop(1).map { it.trim() }

            // New Arduino format:
            // 0  = millis
            // 1  = ax
            // 2  = ay
            // 3  = az
            // 4  = gx
            // 5  = gy
            // 6  = gz
            // 7  = mmg1
            // 8  = mmg2
            // 9  = mmg3
            // 10 = flex_deg
            // 11 = tilt_deg
            // 12 = q0
            // 13 = q1
            // 14 = q2
            // 15 = q3
            // 16 = flags

            val validTime = values[0].toLongOrNull() != null

            // All sensor and orientation values must be numeric.
            val validSensors = (1..15).all {
                values[it].toFloatOrNull() != null
            }

            // Arduino sends one combined flags integer.
            val flags = values[16].toIntOrNull()

            if (validTime && validSensors &&
                flags != null && calibrationReady) {

                timestamp = SimpleDateFormat(
                    "MM-dd-yyyy.HH.mm.ss.SSS",
                    Locale.US
                ).format(Date())

                // Accelerometer
                ax = values[1]
                ay = values[2]
                az = values[3]

                // Gyroscope
                gx = values[4]
                gy = values[5]
                gz = values[6]

                // MMG sensors
                mmg1 = values[7]
                mmg2 = values[8]
                mmg3 = values[9]

                // Calculated angles
                flexDeg = values[10]
                tiltDeg = values[11]

                // Quaternion
                q0 = values[12].toDouble()
                q1 = values[13].toDouble()
                q2 = values[14].toDouble()
                q3 = values[15].toDouble()

                // Decode combined flags
                accUsed = if ((flags and 1) != 0) "Yes" else "No"
                stationary = if ((flags and 2) != 0) "Yes" else "No"

                Log.d(TAG, "Parsed sensor data successfully")
                Log.d(TAG, "AX=$ax AY=$ay AZ=$az")
                Log.d(TAG, "GX=$gx GY=$gy GZ=$gz")
                Log.d(TAG, "MMG1=$mmg1 MMG2=$mmg2 MMG3=$mmg3")

                // Save CSV only during recording
                if (recording) {
                    try {
                        val csvRow = listOf(timestamp) + values

                        fileOutput?.write(
                            (csvRow.joinToString(",") + "\n")
                                .toByteArray(Charsets.UTF_8)
                        )

                    } catch (e: Exception) {

                        recording = false
                        recordingStatus = "File error: ${e.message}"

                        try {
                            fileOutput?.close()
                        } catch (_: Exception) {
                        }

                        fileOutput = null
                    }
                }

            } else {
                Log.w(
                    TAG,
                    "Invalid DATA message: time=$validTime, " +
                            "sensors=$validSensors, flags=$flags, " +
                            "calibrationReady=$calibrationReady"
                )
            }
        }

    }

    // Open the serial port only after USB permission has been granted.
    val openSerial: (UsbDevice) -> Unit = { device ->
        try {
            val manager =
                context.getSystemService(Context.USB_SERVICE) as UsbManager

            val driver = UsbSerialProber.getDefaultProber()
                .findAllDrivers(manager)
                .firstOrNull { it.device.deviceId == device.deviceId }

            if (driver == null) {
                connectionStatus = "No compatible serial driver"
            } else {
                val connection = manager.openDevice(device)

                if (connection == null) {
                    connectionStatus = "Could not open USB device"
                } else {
                    try {
                        val port = driver.ports[0]
                        port.open(connection)
                        port.setParameters(
                            115200,
                            8,
                            UsbSerialPort.STOPBITS_1,
                            UsbSerialPort.PARITY_NONE
                        )

                        // Some USB serial devices need DTR enabled.
                        try {
                            port.setDTR(true)
                        } catch (_: Exception) {
                        }

                        serialPort = port
                        usbConnection = connection
                        connectionStatus = "Connected — receiving data"
                        calibrationStarted = false
                        calibrationReady = false
                        calibrationError = false
                        calibrationRemaining = 10

                        calibrationStatus = "Preparing gyroscope calibration..."

                        clearSensorValues()

                        readJob?.cancel()
                        readJob = scope.launch(Dispatchers.IO) {
                            val buffer = ByteArray(256)
                            val pending = StringBuilder()

                            try {
                                while (isActive) {
                                    val count = port.read(buffer, 1000)

                                    if (count > 0) {
                                        Log.d(TAG, "USB bytes received: $count")
                                        val chunk = String(
                                            buffer,
                                            0,
                                            count,
                                            Charsets.UTF_8
                                        )

                                        pending.append(chunk)

                                        while (true) {
                                            val newline = pending.indexOf("\n")
                                            if (newline < 0) break

                                            val line = pending
                                                .substring(0, newline)
                                                .trim()
                                            pending.delete(0, newline + 1)

                                            if (line.isNotBlank()) {
                                                withContext(Dispatchers.Main) {
                                                    processLine(line)
                                                }
                                            }
                                        }

                                        // Prevent an endlessly growing buffer
                                        // if the Arduino does not send newlines.
                                        if (pending.length > 4096) {
                                            pending.clear()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "USB serial reading error", e)
                                withContext(Dispatchers.Main) {
                                    if (serialPort === port) {
                                        connectionStatus =
                                            "Serial read stopped: ${e.message}"
                                    }
                                }
                            }
                        }

                    } catch (e: Exception) {
                        try {
                            connection.close()
                        } catch (_: Exception) {
                        }
                        connectionStatus = "Serial error: ${e.message}"
                    }
                }
            }
        } catch (e: Exception) {
            connectionStatus = "USB error: ${e.message}"
        }
    }

    // Receive Android's USB permission result.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != USB_PERMISSION_ACTION) return

                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                )

                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE,
                            UsbDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                if (granted && device != null) {
                    openSerial(device)
                } else {
                    connectionStatus = "USB permission denied"
                }
            }
        }

        val filter = IntentFilter(USB_PERMISSION_ACTION)

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }

            readJob?.cancel()
            recording = false

            try {
                fileOutput?.flush()
            } catch (_: Exception) {
            }
            try {
                fileOutput?.close()
            } catch (_: Exception) {
            }
            fileOutput = null

            try {
                serialPort?.close()
            } catch (_: Exception) {
            }
            serialPort = null

            try {
                usbConnection?.close()
            } catch (_: Exception) {
            }
            usbConnection = null
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Gait IMU Recorder",
                style = MaterialTheme.typography.headlineMedium
            )
            Text("BMI270 • USB Serial • 115200 baud")
            HorizontalDivider()

            Text("Connection: $connectionStatus")

            HorizontalDivider()

            Text(
                "Gyroscope Calibration",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                calibrationStatus,
                style = MaterialTheme.typography.titleMedium
            )

            if (calibrationStarted && !calibrationReady && !calibrationError) {

                Text(
                    "$calibrationRemaining seconds remaining",
                    style = MaterialTheme.typography.headlineMedium
                )

            } else if (calibrationReady) {

                Text(
                    "✓ Calibration Complete",
                    style = MaterialTheme.typography.titleMedium
                )

            } else if (calibrationError) {

                Text(
                    "Please check the sensor and restart.",
                    style = MaterialTheme.typography.bodyMedium
                )

            } else {

                Text(
                    "Waiting for Arduino to start calibration...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = patientName,
                onValueChange = { patientName = it },
                label = { Text("Patient name") },
                placeholder = { Text("Enter patient name") },
                singleLine = true,
                enabled = !recording && serialPort == null,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                enabled = serialPort == null &&
                        !recording &&
                        patientName.isNotBlank(),
                onClick = {
                    calibrationStarted = false
                    calibrationReady = false
                    calibrationError = false
                    calibrationRemaining = 10
                    calibrationStatus = "Waiting for Arduino..."

                    try {
                        val manager =
                            context.getSystemService(Context.USB_SERVICE)
                                    as UsbManager

                        val drivers =
                            UsbSerialProber.getDefaultProber()
                                .findAllDrivers(manager)

                        if (drivers.isEmpty()) {
                            connectionStatus =
                                "No compatible serial device found"
                        } else {
                            val driver = drivers[0]
                            val device = driver.device

                            if (manager.hasPermission(device)) {
                                openSerial(device)
                            } else {
                                val permissionIntent = PendingIntent.getBroadcast(
                                    context,
                                    0,
                                    Intent(USB_PERMISSION_ACTION).setPackage(
                                        context.packageName
                                    ),
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                            PendingIntent.FLAG_MUTABLE
                                )

                                manager.requestPermission(
                                    device,
                                    permissionIntent
                                )
                                connectionStatus = "Waiting for USB permission"
                            }
                        }
                    } catch (e: Exception) {
                        connectionStatus = "USB error: ${e.message}"
                    }
                }
            ) {
                Text("Connect USB")
            }

            Button(
                enabled = serialPort != null,
                onClick = {
                    calibrationReady = false
                    calibrationError = false
                    calibrationRemaining = 10
                    calibrationStatus = "Waiting for Arduino calibration status..."

                    readJob?.cancel()
                    readJob = null

                    try {
                        serialPort?.close()
                    } catch (_: Exception) {
                    }
                    serialPort = null

                    try {
                        usbConnection?.close()
                    } catch (_: Exception) {
                    }
                    usbConnection = null

                    val wasRecording = recording
                    recording = false
                    try {
                        fileOutput?.flush()
                    } catch (_: Exception) {
                    }
                    try {
                        fileOutput?.close()
                    } catch (_: Exception) {
                    }
                    fileOutput = null
                    recordingStatus = if (wasRecording) {
                        "Saved partial recording: ${csvFile?.name ?: "CSV file"}"
                    } else {
                        "Not recording"
                    }
                    connectionStatus = "Disconnected"
                }
            ) {
                Text("Disconnect USB")
            }

            HorizontalDivider()

            if (calibrationReady) {

                Text("Timestamp: $timestamp")

                Text("Accelerometer (g)")
                Text("AX: $ax     AY: $ay     AZ: $az")

                Text("Gyroscope (°/s)")
                Text("GX: $gx     GY: $gy     GZ: $gz")

                HorizontalDivider()

                Text(
                    "MMG Sensors",
                    style = MaterialTheme.typography.titleMedium
                )

                Text("MMG 1: $mmg1")
                Text("MMG 2: $mmg2")
                Text("MMG 3: $mmg3")

                HorizontalDivider()


                Text(
                    "Quaternion Orientation",
                    style = MaterialTheme.typography.titleMedium
                )

                Text("Q0: $q0")
                Text("Q1: $q1")
                Text("Q2: $q2")
                Text("Q3: $q3")

                HorizontalDivider()

                Text(
                    "Calculated Angles",
                    style = MaterialTheme.typography.titleMedium
                )

                Text("Flexion: $flexDeg°")
                Text("Tilt: $tiltDeg°")

                HorizontalDivider()

                Text(
                    "Sensor Status",
                    style = MaterialTheme.typography.titleMedium
                )

                Text("Accelerometer Used: $accUsed")
                Text("Stationary: $stationary")

            } else {

                Text("Sensor readings will appear after calibration.")
            }

            Text("Recording: $recordingStatus")

            Text(
                text = "Firebase: $uploadStatus",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                enabled = serialPort != null &&
                        !recording,
                onClick = {
                    if (!calibrationReady) {

                        try {
                            calibrationStarted = false
                            calibrationError = false
                            calibrationRemaining = 10
                            calibrationStatus = "Sending calibration command to Arduino..."

//                            val command = "CALIBRATE\n".toByteArray(Charsets.UTF_8)
//
//                            serialPort?.write(command, 1000)

                            val command = "CALIBRATE\n".toByteArray(Charsets.UTF_8)

                            Log.d(TAG, "Sending calibration command to Arduino")

                            val bytesWritten = serialPort?.write(command, 1000)

                            Log.d(TAG, "Calibration command write completed: $bytesWritten bytes")

                        } catch (e: Exception) {

                            calibrationStatus = "Calibration command failed: ${e.message}"
                            calibrationError = true
                        }

                    } else {
                        val enteredPatientName = patientName.trim()

                        if (enteredPatientName.isBlank()) {
                            recordingStatus = "Please enter the patient name first."
                        } else {
                            try {
                                val fileTimestamp = SimpleDateFormat(
                                    "yyyyMMdd_HHmmss",
                                    Locale.US
                                ).format(Date())

                                val safePatientName = enteredPatientName.replace(
                                    Regex("[^a-zA-Z0-9_-]"),
                                    "_"
                                )

                                val name = "${safePatientName}_${fileTimestamp}.csv"

                                val file = File(context.filesDir, name)

                                fileOutput = context.openFileOutput(
                                    name,
                                    Context.MODE_PRIVATE
                                )

                                fileOutput?.write(
                                    "timestamp,millis,ax,ay,az,gx,gy,gz,mmg1,mmg2,mmg3,flex_deg,tilt_deg,q0,q1,q2,q3,flags\n"
                                        .toByteArray(Charsets.UTF_8)
                                )

                                csvFile = file
                                recording = true
                                recordingStatus = "Recording: $enteredPatientName"

                            } catch (e: Exception) {
                                recording = false
                                recordingStatus = "File error: ${e.message}"

                                try {
                                    fileOutput?.close()
                                } catch (_: Exception) {
                                }

                                fileOutput = null
                            }
                        }
                    }
                }
            ) {
                Text(
                    if (calibrationReady) {
                        "Start Recording"
                    } else {
                        "Calibrate for New Session"
                    }
                )
            }


            Button(
                enabled = recording,
                onClick = {
                    var saveSuccessful = false

                    // Capture these before clearing the patient name
                    val savedPatientName = patientName.trim()
                    val savedCsvFile = csvFile

                    try {
                        fileOutput?.flush()
                            ?: throw Exception("Recording file is not open")

                        fileOutput?.close()
                        fileOutput = null

                        saveSuccessful = true
                        recordingStatus =
                            "Saved: ${savedCsvFile?.name ?: "CSV file"}"

                    } catch (e: Exception) {
                        recordingStatus = "Save/close error: ${e.message}"

                        try {
                            fileOutput?.close()
                        } catch (_: Exception) {
                        }

                        fileOutput = null

                    } finally {
                        recording = false

                        // Reset calibration state for the next session
                        calibrationStarted = false
                        calibrationReady = false
                        calibrationError = false
                        calibrationRemaining = 10

                        calibrationStatus = "Calibration required for next session"

                        if (saveSuccessful) {
                            patientName = ""
                        }
                    }

                    // Upload only after the CSV has been saved successfully
                    if (saveSuccessful && savedCsvFile != null) {
                        scope.launch {
                            try {
                                uploadStatus = "Uploading CSV to Firebase..."

                                val result = firebaseRepository.uploadCsv(
                                    csvFile = savedCsvFile,
                                    patientName = savedPatientName
                                )

                                uploadStatus = "Upload successful!"
                                recordingStatus =
                                    "Saved and uploaded: ${savedCsvFile.name}"

                            } catch (e: Exception) {
                                uploadStatus = "Upload failed: ${e.message}"
                                recordingStatus =
                                    "CSV saved locally, but Firebase upload failed."
                            }
                        }
                    }
                }
            ) {
                Text("Stop Recording")
            }


            Text(
                "CSV files are saved in the app's private files folder.",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                enabled = !recording && csvFile != null,
                onClick = {
                    val name = csvFile?.name ?: "gait_recording.csv"
                    exportLauncher.launch(name)
                }
            ) {
                Text("Export CSV to Downloads")
            }

        }
    }
}
