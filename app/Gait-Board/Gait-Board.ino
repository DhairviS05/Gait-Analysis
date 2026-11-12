#include <Arduino_BMI270_BMM150.h>

// =====================================================
// GYROSCOPE CALIBRATION VARIABLES
// =====================================================

float gyroBiasX = 0.0;
float gyroBiasY = 0.0;
float gyroBiasZ = 0.0;

bool calibrationComplete = false;

// =====================================================
// MMG SENSOR VARIABLES
// Preserving your existing processing logic
// =====================================================

int newval[3] = {0, 0, 0};
int derivative[3] = {0, 0, 0};

int oldval_1[3] = {0, 0, 0};
int oldval_2[3] = {0, 0, 0};
int oldval_3[3] = {0, 0, 0};
int oldval_4[3] = {0, 0, 0};
int oldval_5[3] = {0, 0, 0};

const int mmgPins[3] = {A0, A1, A2};

// =====================================================
// GYROSCOPE CALIBRATION FUNCTION
// Runs whenever Android sends CALIBRATE
// =====================================================

void calibrateGyroscope() {

  const unsigned long CALIBRATION_TIME = 10000;

  unsigned long startTime = millis();
  unsigned long lastReportedSecond = 0;

  unsigned long sampleCount = 0;

  float sumX = 0.0;
  float sumY = 0.0;
  float sumZ = 0.0;

  // Calibration is not complete until this run finishes.
  calibrationComplete = false;

  // Reset previous bias values.
  gyroBiasX = 0.0;
  gyroBiasY = 0.0;
  gyroBiasZ = 0.0;

  Serial.println("STATUS,CALIBRATION_STARTED");
  Serial.println("STATUS,CALIBRATION,10");

  while (millis() - startTime < CALIBRATION_TIME) {

    unsigned long elapsed = millis() - startTime;

    // Send countdown updates approximately once per second.
    unsigned long elapsedSecond = elapsed / 1000;

    if (elapsedSecond != lastReportedSecond) {

      lastReportedSecond = elapsedSecond;

      int remainingSeconds =
          10 - (int)elapsedSecond;

      if (remainingSeconds > 0) {

        Serial.print("STATUS,CALIBRATION,");
        Serial.println(remainingSeconds);
      }
    }

    // Collect gyroscope readings whenever available.
    if (IMU.gyroscopeAvailable()) {

      float gx, gy, gz;

      IMU.readGyroscope(gx, gy, gz);

      sumX += gx;
      sumY += gy;
      sumZ += gz;

      sampleCount++;
    }
  }

  // Check whether sufficient readings were collected.
  if (sampleCount < 20) {

    Serial.println("STATUS,CALIBRATION_ERROR");

    calibrationComplete = false;

    return;
  }

  // Calculate fresh gyroscope bias for this session.
  gyroBiasX = sumX / sampleCount;
  gyroBiasY = sumY / sampleCount;
  gyroBiasZ = sumZ / sampleCount;

  calibrationComplete = true;

  Serial.println("STATUS,CALIBRATION_COMPLETE");
}

// =====================================================
// SETUP
// =====================================================

void setup() {

  Serial.begin(115200);

  analogReadResolution(12);

  // Initialize BMI270 IMU.
  if (!IMU.begin()) {

    Serial.println("STATUS,IMU_ERROR");

    while (true) {
      delay(1000);
    }
  }

  // Allow the IMU to initialize.
  delay(2000);

  // DO NOT calibrate here.
  // Wait for Android to send CALIBRATE.

  calibrationComplete = false;
}

// =====================================================
// READ COMMANDS FROM ANDROID
// =====================================================

void checkSerialCommand() {

  static String incomingCommand = "";

  while (Serial.available() > 0) {

    char receivedChar = Serial.read();

    if (receivedChar == '\n') {

      incomingCommand.trim();

      if (incomingCommand == "CALIBRATE") {

        calibrateGyroscope();
      }

      incomingCommand = "";
    }

    else if (receivedChar != '\r') {

      incomingCommand += receivedChar;
    }
  }
}

// =====================================================
// LOOP
// =====================================================

void loop() {

  // ALWAYS check Android commands, even during recording.
  checkSerialCommand();

  // Do not send sensor data until calibration completes.
  if (!calibrationComplete) {
    return;
  }

  // -----------------------------------------------
  // 1. Read MMG sensors
  // -----------------------------------------------

  for (int i = 0; i < 3; i++) {

    newval[i] = analogRead(mmgPins[i]);

    derivative[i] = newval[i] - oldval_5[i];

    if (derivative[i] < 0) {
      derivative[i] = 0;
    }

    oldval_5[i] = oldval_4[i];
    oldval_4[i] = oldval_3[i];
    oldval_3[i] = oldval_2[i];
    oldval_2[i] = oldval_1[i];
    oldval_1[i] = newval[i];

    delay(10);
  }

  // -----------------------------------------------
  // 2. Read IMU
  // -----------------------------------------------

  if (IMU.accelerationAvailable() &&
      IMU.gyroscopeAvailable()) {

    float ax, ay, az;
    float gx, gy, gz;

    IMU.readAcceleration(ax, ay, az);
    IMU.readGyroscope(gx, gy, gz);

    gx -= gyroBiasX;
    gy -= gyroBiasY;
    gz -= gyroBiasZ;

    // ---------------------------------------------
    // 3. Send data
    // ---------------------------------------------

    Serial.print("DATA,");
    Serial.print(millis());
    Serial.print(",");

    Serial.print(ax, 4);
    Serial.print(",");
    Serial.print(ay, 4);
    Serial.print(",");
    Serial.print(az, 4);
    Serial.print(",");

    Serial.print(gx, 4);
    Serial.print(",");
    Serial.print(gy, 4);
    Serial.print(",");
    Serial.print(gz, 4);

    for (int i = 0; i < 3; i++) {
      Serial.print(",");
      Serial.print(derivative[i]);
    }

    Serial.println();
  }
}