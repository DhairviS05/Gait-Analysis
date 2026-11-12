int newval1 = 0, derivative1 = 0, oldval_51 = 0, oldval_41 = 0, oldval_31 = 0, oldval_21 = 0, oldval_11 = 0;
int newval2 = 0, derivative2 = 0, oldval_52 = 0, oldval_42 = 0, oldval_32 = 0, oldval_22 = 0, oldval_12 = 0;
int newval3 = 0, derivative3 = 0, oldval_53 = 0, oldval_43 = 0, oldval_33 = 0, oldval_23 = 0, oldval_13 = 0;
int newval4 = 0, derivative4 = 0, oldval_54 = 0, oldval_44 = 0, oldval_34 = 0, oldval_24 = 0, oldval_14 = 0;
int newval5 = 0, derivative5 = 0, oldval_55 = 0, oldval_45 = 0, oldval_35 = 0, oldval_25 = 0, oldval_15 = 0;
int newval6 = 0, derivative6 = 0, oldval_56 = 0, oldval_46 = 0, oldval_36 = 0, oldval_26 = 0, oldval_16 = 0;
int pos1 = 0;
int pos2 = 0;
int mmg1 = 0;
int mmg2 = 0;
int derivative[6] = { 0, 0, 0, 0, 0, 0 };
void setup() {
  Serial.begin(9600);
  analogReadResolution(12); 
}

String padding(int number, byte width) {
  int currentMax = 10;
  String padd = String();
  for (byte i = 1; i < width; i++) {
    if (number < currentMax) {
      padd.concat("0");
    }
    currentMax *= 10;
  }
  padd.concat(number);
  return padd;
}

void loop() {

  // Read Data.
  newval1 = analogRead(A0);
  derivative[0] = (newval1 - oldval_51);
  delay(10);
  newval2 = analogRead(A1);
  derivative[1] = (newval2 - oldval_52);
  delay(10);
  newval3 = analogRead(A2);
  derivative[2] = (newval3 - oldval_53);
  delay(10);
  newval4 = analogRead(A3);
  derivative[3] = (newval4 - oldval_54);
  delay(10);
  newval5 = analogRead(A6);
  derivative[4] = (newval5 - oldval_55);
  delay(10);
  newval6 = analogRead(A7);
  derivative[5] = (newval6 - oldval_56);
  delay(10);

  if (derivative[0] < 0) {
    derivative[0] = 0;
  }
  if (derivative[1] < 0) {
    derivative[1] = 0;
  }
  if (derivative[2] < 0) {
    derivative[2] = 0;
  }
  if (derivative[3] < 0) {
    derivative[3] = 0;
  }
  if (derivative[4] < 0) {
    derivative[4] = 0;
  }
  if (derivative[5] < 0) {
    derivative[5] = 0;
  }

  oldval_51 = oldval_41;
  oldval_52 = oldval_42;
  oldval_53 = oldval_43;
  oldval_54 = oldval_44;
  oldval_55 = oldval_45;
  oldval_56 = oldval_46;

  oldval_41 = oldval_31;
  oldval_42 = oldval_32;
  oldval_43 = oldval_33;
  oldval_44 = oldval_34;
  oldval_45 = oldval_35;
  oldval_46 = oldval_36;

  oldval_31 = oldval_21;
  oldval_32 = oldval_22;
  oldval_33 = oldval_23;
  oldval_34 = oldval_24;
  oldval_35 = oldval_25;
  oldval_36 = oldval_26;

  oldval_21 = oldval_11;
  oldval_22 = oldval_12;
  oldval_23 = oldval_13;
  oldval_24 = oldval_14;
  oldval_25 = oldval_15;
  oldval_26 = oldval_16;

  oldval_11 = newval1;
  oldval_12 = newval2;
  oldval_13 = newval3;
  oldval_14 = newval4;
  oldval_15 = newval5;
  oldval_16 = newval6;

  // Concatenate the String and Send Data.
  String s_all_Data = String();
  s_all_Data.concat(padding(derivative[0], 4));
  s_all_Data.concat(padding(derivative[1], 4));
  s_all_Data.concat(padding(derivative[2], 4));
  s_all_Data.concat(padding(derivative[3], 4));
  s_all_Data.concat(padding(derivative[4], 4));
  s_all_Data.concat(padding(derivative[5], 4));
  Serial.println(s_all_Data);
  s_all_Data = "";

}
