
package com.example.gaitimudemo

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
import com.example.gaitimudemo.ui.theme.GaitIMUDemoTheme
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
    "com.example.gaitimudemo.USB_PERMISSION"

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

    // Process one complete CSV line from the Arduino.
//    val processLine: (String) -> Unit = { line ->
//        val parts = line.trim().split(",")
//
//        if (parts.size == 7) {
//            val numbers = parts.map { it.trim().toFloatOrNull() }
//
//            if (numbers.all { it != null }) {
//                timestamp = parts[0]
//                ax = parts[1]
//                ay = parts[2]
//                az = parts[3]
//                gx = parts[4]
//                gy = parts[5]
//                gz = parts[6]
//
//                if (recording) {
//                    try {
//                        fileOutput?.write(
//                            (line.trim() + "\n").toByteArray(Charsets.UTF_8)
//                        )
//                        fileOutput?.flush()
//                    } catch (e: Exception) {
//                        recording = false
//                        recordingStatus = "File error: ${e.message}"
//                        try {
//                            fileOutput?.close()
//                        } catch (_: Exception) {
//                        }
//                        fileOutput = null
//                    }
//                }
//            }
//        }
//    }

    // Arduino format:
// DATA,time_ms,ax,ay,az,gx,gy,gz,mmg1,mmg2,mmg3
    val processLine: (String) -> Unit = { line ->
        val parts = line.trim().split(",")

        // Expect 11 fields: DATA + 10 numeric values.
        if (parts.size == 11 && parts[0].trim() == "DATA") {
            val values = parts.drop(1).map { it.trim() }

            if (values.all { it.toFloatOrNull() != null }) {

                // Generate the calendar timestamp using the phone's clock.
                timestamp = SimpleDateFormat(
                    "MM-dd-yyyy.HH.mm.ss",
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

                // Three MMG sensors
                mmg1 = values[7]
                mmg2 = values[8]
                mmg3 = values[9]

                // Save calendar timestamp and sensor values.
                // Do not save the Arduino millis() value.
                if (recording) {
                    try {
                        val csvRow = listOf(timestamp) + values.drop(1)

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

                        readJob?.cancel()
                        readJob = scope.launch(Dispatchers.IO) {
                            val buffer = ByteArray(256)
                            val pending = StringBuilder()

                            try {
                                while (isActive) {
                                    val count = port.read(buffer, 1000)

                                    if (count > 0) {
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

            OutlinedTextField(
                value = patientName,
                onValueChange = { patientName = it },
                label = { Text("Patient name") },
                placeholder = { Text("Enter patient name") },
                singleLine = true,
                enabled = !recording,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                enabled = serialPort == null,
                onClick = {
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

            Text("Recording: $recordingStatus")

            Button(
                enabled = serialPort != null &&
                        !recording &&
                        patientName.isNotBlank(),
                onClick = {
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
                                "timestamp,ax,ay,az,gx,gy,gz,mmg1,mmg2,mmg3\n"
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
            ) {
                Text("Start Recording")
            }

            Button(
                enabled = recording,
                onClick = {
                    var saveSuccessful = false

                    try {
                        fileOutput?.flush()
                            ?: throw Exception("Recording file is not open")

                        fileOutput?.close()
                        fileOutput = null

                        saveSuccessful = true
                        recordingStatus =
                            "Saved: ${csvFile?.name ?: "CSV file"}"

                    } catch (e: Exception) {
                        recordingStatus = "Save/close error: ${e.message}"

                        try {
                            fileOutput?.close()
                        } catch (_: Exception) {
                        }

                        fileOutput = null

                    } finally {
                        recording = false

                        if (saveSuccessful) {
                            patientName = ""
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
