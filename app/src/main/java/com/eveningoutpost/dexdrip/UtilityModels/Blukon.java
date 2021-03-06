package com.eveningoutpost.dexdrip.UtilityModels;

import android.app.Activity;
import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.Models.TransmitterData;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.Services.DexCollectionService;
import com.eveningoutpost.dexdrip.utils.CipherUtils;
import com.eveningoutpost.dexdrip.xdrip;

/**
 * Created by gregorybel / jamorham on 02/09/2017.
 */

public class Blukon {

    private static final String TAG = "Blukon";
    private static final String BLUKON_PIN_PREF = "Blukon-bluetooth-pin";

    private static int m_nowGlucoseOffset = 0;
    private static String currentCommand = "";
    //TO be used later
    private static enum BLUKON_STATES {
        INITIAL
    }
    private static int m_getNowGlucoseDataIndexCommand = 0;
    private static int m_gotOneTimeUnknownCmd = 0;

    public static String getPin() {
        final String thepin = Home.getPreferencesStringWithDefault(BLUKON_PIN_PREF, null);
        if ((thepin != null) && (thepin.length() < 3))
            return null; // TODO enforce sane minimum pin length
        return thepin;
    }

    private static void setPin(String thepin) {
        if (thepin == null) return;
        Home.setPreferencesString(BLUKON_PIN_PREF, thepin);
    }

    public static void clearPin() {
        Home.removePreferencesItem(BLUKON_PIN_PREF);
    }

    public static void initialize() {
        Home.setPreferencesInt("bridge_battery", 100); //force battery to 100% before first reading
        Home.setPreferencesInt("nfc_sensor_age", 0); //force sensor age to no-value before first reading
        m_gotOneTimeUnknownCmd = 0;
        PersistentStore.setLong("blukon-4-hourly-scan", JoH.tsl());
    }

    public static boolean isBlukonPacket(byte[] buffer) {
    /* -53  0xCB -117 0x8B */
        return !((buffer == null) || (buffer.length < 3)) && (buffer[0] == (byte) 0xCB || buffer[0] == (byte) 0x8B);
    }

    public static boolean checkBlukonPacket(byte[] buffer) {
        return isBlukonPacket(buffer) && getPin() != null; // TODO can't be unset yet and isn't proper subtype test yet
    }


    // .*(dexdrip|gatt|Blukon).
    public static byte[] decodeBlukonPacket(byte[] buffer) {
        int cmdFound = 0;

        if (buffer == null) {
            UserError.Log.e(TAG, "null buffer passed to decodeBlukonPacket");
            return null;
        }

        //BluCon code by gregorybel
        final String strRecCmd = CipherUtils.bytesToHex(buffer).toLowerCase();
        UserError.Log.i(TAG, "BlueCon data: " + strRecCmd);

        if (strRecCmd.equalsIgnoreCase("cb010000")) {
            UserError.Log.i(TAG, "wakeup received");
            currentCommand = "";
            cmdFound = 1;
        }

        // BluconACKRespons will come in two different situations
        // 1) after we have sent an ackwakeup command
        // 2) after we have a sleep command
        if (strRecCmd.startsWith("8b0a00")) {
            cmdFound = 1;
            UserError.Log.i(TAG, "Got ACK");

            if (currentCommand.startsWith("810a00")) {//ACK sent
                //ack received

                //This command will be asked only one time after first connect and never again
                if (m_gotOneTimeUnknownCmd == 0) {
                    currentCommand = "010d0b00";
                    UserError.Log.i(TAG, "getUnknownCmd1: " + currentCommand);
                } else {
                    if (JoH.pratelimit("blukon-4-hourly-scan",4 * 3600)) {
                        // do something only once every 4 hours
                        currentCommand = "010d0e0127";
                        UserError.Log.i(TAG, "getSensorAge");
                    } else {
                        currentCommand = "010d0e0103";
                        m_getNowGlucoseDataIndexCommand = 1;//to avoid issue when gotNowDataIndex cmd could be same as getNowGlucoseData (case block=3)
                        UserError.Log.i(TAG, "getNowGlucoseDataIndexCommand");
                    }
                }

            } else {
                UserError.Log.i(TAG, "Got sleep ack, resetting initialstate!");
                currentCommand = "";
            }
        }

        if (strRecCmd.startsWith("8b1a02")) {
            cmdFound = 1;
            UserError.Log.e(TAG, "Got NACK on cmd=" + currentCommand + " with error=" + strRecCmd.substring(6));

            if (strRecCmd.startsWith("8b1a020014")) {
                UserError.Log.e(TAG, "Timeout: please wait 5min or push button to restart!");
            }

            if (strRecCmd.startsWith("8b1a02000f")) {
                UserError.Log.e(TAG, "Libre sensor has been removed!");
            }

            m_gotOneTimeUnknownCmd = 0;
            currentCommand = "";
            PersistentStore.setLong("blukon-4-hourly-scan", JoH.tsl()); // set to current time to force timer to be set back
        }

        if (currentCommand.equals("") && strRecCmd.equalsIgnoreCase("cb010000")) {
            cmdFound = 1;
            UserError.Log.i(TAG, "wakeup received");

            currentCommand = "010d0900";
            UserError.Log.i(TAG, "getPatchInfo");

        } else if (currentCommand.startsWith("010d0900") /*getPatchInfo*/ && strRecCmd.startsWith("8bd9")) {
            cmdFound = 1;
            UserError.Log.i(TAG, "Patch Info received");
            currentCommand = "810a00";
            UserError.Log.i(TAG, "Send ACK");

        } else if (currentCommand.startsWith("010d0b00") /*getUnknownCmd1*/ && strRecCmd.startsWith("8bdb")) {
            cmdFound = 1;
            UserError.Log.w(TAG, "gotUnknownCmd1 (010d0b00): "+strRecCmd);

            currentCommand = "010d0a00";
            UserError.Log.i(TAG, "getUnknownCmd2 "+ currentCommand);

        } else if (currentCommand.startsWith("010d0a00") /*getUnknownCmd2*/ && strRecCmd.startsWith("8bda")) {
            cmdFound = 1;
            UserError.Log.w(TAG, "gotUnknownCmd2 (010d0a00): "+strRecCmd);
            m_gotOneTimeUnknownCmd = 1;

            currentCommand = "010d0e0127";
            UserError.Log.i(TAG, "getSensorAge");

        } else if (currentCommand.startsWith("010d0e0127") /*getSensorAge*/ && strRecCmd.startsWith("8bde")) {
            cmdFound = 1;
            UserError.Log.i(TAG, "SensorAge received");

            int sensorAge = sensorAge(buffer);

            if ((sensorAge > 0) && (sensorAge < 200000)) {
                Home.setPreferencesInt("nfc_sensor_age", sensorAge);//in min
            }
            currentCommand = "010d0e0103";
            m_getNowGlucoseDataIndexCommand = 1;//to avoid issue when gotNowDataIndex cmd could be same as getNowGlucoseData (case block=3)
            UserError.Log.i(TAG, "getNowGlucoseDataIndexCommand");

        } else if (currentCommand.startsWith("010d0e0103") /*getNowDataIndex*/ && m_getNowGlucoseDataIndexCommand == 1 && strRecCmd.startsWith("8bde")) {
            cmdFound = 1;
            UserError.Log.i(TAG, "gotNowDataIndex");

            int blockNumber = blockNumberForNowGlucoseData(buffer);
            UserError.Log.i(TAG, "block Number is "+blockNumber);

            currentCommand = "010d0e010"+ Integer.toHexString(blockNumber);//getNowGlucoseData
            m_getNowGlucoseDataIndexCommand = 0;

            UserError.Log.i(TAG, "getNowGlucoseData");


        } else if (currentCommand.startsWith("010d0e01") /*getNowGlucoseData*/ && strRecCmd.startsWith("8bde")) {
            cmdFound = 1;
            int currentGlucose = nowGetGlucoseValue(buffer);

            UserError.Log.i(TAG, "*****************got getNowGlucoseData = " + currentGlucose);

            processNewTransmitterData(TransmitterData.create(currentGlucose, currentGlucose, 0 /*battery level force to 0 as unknown*/, JoH.tsl()));

            currentCommand = "010c0e00";
            UserError.Log.i(TAG, "Send sleep cmd");

        }  else if (strRecCmd.startsWith("cb020000")) {
            cmdFound = 1;
            UserError.Log.e(TAG, "is bridge battery low????!");
            Home.setPreferencesInt("bridge_battery", 50);

        } else if (strRecCmd.startsWith("cbdb0000")) {
            cmdFound = 1;
            UserError.Log.e(TAG, "is bridge battery really low????!");
            Home.setPreferencesInt("bridge_battery", 25);

        }



        if (currentCommand.length() > 0 && cmdFound == 1) {
            UserError.Log.d(TAG, "Sending reply: " + currentCommand);
            return CipherUtils.hexToBytes(currentCommand);
        } else {
            if (cmdFound == 0) {
                UserError.Log.e(TAG, "************COMMAND NOT FOUND! -> " + strRecCmd);
            }

            return null;
        }

    }



    private static synchronized void processNewTransmitterData(TransmitterData transmitterData) {
        if (transmitterData == null) {
            UserError.Log.e(TAG, "Got duplicated data!");
            return;
        }

        final Sensor sensor = Sensor.currentSensor();
        if (sensor == null) {
            UserError.Log.i(TAG, "processNewTransmitterData: No Active Sensor, Data only stored in Transmitter Data");
            return;
        }

        DexCollectionService.last_transmitter_Data = transmitterData;
        UserError.Log.d(TAG, "BgReading.create: new BG reading at " + transmitterData.timestamp + " with a timestamp of " + transmitterData.timestamp);
        BgReading.create(transmitterData.raw_data, transmitterData.filtered_data, xdrip.getAppContext(), transmitterData.timestamp);
    }

    /* @keencave
     * extract trend index from FRAM block #3 from the libre sensor
     * input: blucon answer to trend index request, including 6 starting protocol bytes
     * return: 2 byte containing the next absolute block index to be read from
     * the libre sensor
     */

    private static int blockNumberForNowGlucoseData(byte[] input) {
        int nowGlucoseIndex2 = 0;
        int nowGlucoseIndex3 = 0;

        nowGlucoseIndex2 = (int) input[5];

        // calculate byte position in sensor body
        nowGlucoseIndex2 = (nowGlucoseIndex2 * 6) + 4;

        // decrement index to get the index where the last valid BG reading is stored
        nowGlucoseIndex2 -= 6;
        // adjust round robin
        if (nowGlucoseIndex2 < 4)
            nowGlucoseIndex2 = nowGlucoseIndex2 + 96;

        // calculate the absolute block number which correspond to trend index
        nowGlucoseIndex3 = 3 + (nowGlucoseIndex2 / 8);

        // calculate offset of the 2 bytes in the block
        m_nowGlucoseOffset = nowGlucoseIndex2 % 8;

        UserError.Log.i(TAG, "m_nowGlucoseOffset=" + m_nowGlucoseOffset);

        return (nowGlucoseIndex3);
    }

    /* @keencave
     * rescale raw BG reading to BG data format used in xDrip+
     * use 8.5 devider
     * raw format is in 1000 range
     */
    private static int getGlucose(long rawGlucose) {
        // standard divider for raw Libre data (1000 range)
        return (int) (rawGlucose * Constants.LIBRE_MULTIPLIER);
    }

    /* @keencave
     * extract BG reading from the raw data block containing the most recent BG reading
     * input: bytearray with blucon answer including 3 header protocol bytes
     * uses nowGlucoseOffset to calculate the offset of the two bytes needed
     * return: BG reading as int
     */

    private static int nowGetGlucoseValue(byte[] input) {
        final int curGluc;
        final long rawGlucose;

        // grep 2 bytes with BG data from input bytearray, mask out 12 LSB bits and rescale for xDrip+
        rawGlucose = ((input[3 + m_nowGlucoseOffset + 1] & 0x0F) << 8) | (input[3 + m_nowGlucoseOffset] & 0xFF);
        UserError.Log.i(TAG, "rawGlucose=" + rawGlucose);

        // rescale
        curGluc = getGlucose(rawGlucose);

        return curGluc;
    }


    private static int sensorAge(byte[] input) {
        int sensorAge = ((input[3 + 5] & 0xFF) << 8) | (input[3 + 4] & 0xFF);
        UserError.Log.i(TAG, "sensorAge=" + sensorAge);

        return sensorAge;
    }

    public static void doPinDialog(final Activity activity, final Runnable runnable) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Please enter " + activity.getString(R.string.blukon) + " device PIN number");
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        builder.setView(input);
        builder.setPositiveButton(activity.getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                setPin(input.getText().toString().trim());
                if (getPin() != null) {
                    JoH.static_toast_long("Data source set to: " + activity.getString(R.string.blukon) + " pin: " + getPin());
                    runnable.run();
                } else {
                    JoH.static_toast_long("Invalid pin!");
                }
            }
        });
        builder.setNegativeButton(activity.getString(R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        AlertDialog dialog = builder.create();
        try {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        } catch (NullPointerException e) {
            //
        }
        dialog.show();
    }
}
