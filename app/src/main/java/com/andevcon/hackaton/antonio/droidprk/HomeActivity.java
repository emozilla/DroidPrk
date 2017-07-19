package com.andevcon.hackaton.antonio.droidprk;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.contrib.driver.pwmservo.Servo;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Map;

public class HomeActivity extends Activity {

    private FirebaseDatabase mDatabase;

    //Drivers
    private Servo mServo;
    private ButtonInputDriver mButtonInputDriver;
    private DroidPrkCamera mCamera;

    private Handler mServoHandler;
    private Handler mCameraHandler;
    private HandlerThread mCameraThread;
    private Handler mCloudHandler;
    private HandlerThread mCloudThread;

    public static final String TAG = "DROIDPRK";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
/*
        // We need permission to access the camera
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // A problem occurred auto-granting the permission
            Log.d(TAG, "* ---------- No permissions for camera ------ *");
            return;
        }
*/

        // Creates new handlers and associated threads for devices and networking operations.
        mCameraThread = new HandlerThread("CameraBackground");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        mCloudThread = new HandlerThread("CloudThread");
        mCloudThread.start();
        mCloudHandler = new Handler(mCloudThread.getLooper());

        mServoHandler = new Handler();

        // Set the camera
        mCamera = DroidPrkCamera.getInstance();
        mCamera.initializeCamera(this, mCameraHandler, mOnImageAvailableListener);

        mDatabase = FirebaseDatabase.getInstance();

        // Set the button that replaces the motion detector
        // Set the servo
        try {
            mButtonInputDriver = new ButtonInputDriver(
                    BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_HIGH,
                    KeyEvent.KEYCODE_SPACE);
            mButtonInputDriver.register();

        } catch (IOException e) {
            Log.e(TAG, "Error creating Button", e);
            return; // don't init handler
        }

        // Set the servo
        try {
            mServo = new Servo(BoardDefaults.getPwmPin());
            mServo.setAngleRange(-45f, 45f);
            mServo.setEnabled(true);
        } catch (IOException e) {
            Log.e(TAG, "Error creating Servo", e);
            return; // don't init handler
        }

        // App start
        Log.v(TAG, "*************** Let's Rock & Roll ****************" +
                ".____             __  /\\                                __     /\\                       .__   .__   \n" +
                "|    |     ____ _/  |_)/______ _______   ____    ____  |  | __ )/____   _______   ____  |  |  |  |  \n" +
                "|    |   _/ __ \\\\   __\\/  ___/ \\_  __ \\ /  _ \\ _/ ___\\ |  |/ /  /    \\  \\_  __ \\ /  _ \\ |  |  |  |  \n" +
                "|    |___\\  ___/ |  |  \\___ \\   |  | \\/(  <_> )\\  \\___ |    <  |   |  \\  |  | \\/(  <_> )|  |__|  |__\n" +
                "|_______ \\\\___  >|__| /____  >  |__|    \\____/  \\___  >|__|_ \\ |___|  /  |__|    \\____/ |____/|____/\n" +
                "        \\/    \\/           \\/                       \\/      \\/      \\/                              " +
                "" +
                "");
        Log.v(TAG, "*************** Let's Rock & Roll ****************");

    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            Log.v(TAG, "Car sensor detected (Just a button right now)");
            //mServoHandler.post(mOpenTheGates);

            mCamera.takePicture();

            return true;
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mButtonInputDriver != null) {
            mButtonInputDriver.unregister();
            try {
                mButtonInputDriver.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing Button driver", e);
            } finally{
                mButtonInputDriver = null;
            }
        }

        if (mServoHandler != null) {
            mServoHandler.removeCallbacks(mOpenTheGates);
        }
        if (mServo != null) {
            try {
                mServo.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing Servo");
            } finally {
                mServo = null;
            }
        }

        mCamera.shutDown();

        mCameraThread.quitSafely();
        mCloudThread.quitSafely();

    }

    private Runnable mOpenTheGates = new Runnable() {

        private static final long DELAY_MS = 5000L; // 5 seconds
        private double mAngle = Float.NEGATIVE_INFINITY;

        @Override
        public void run() {

            if (mServo == null) {
                return;
            }

            try {
                mServo.setAngle(mServo.getMaximumAngle()); // Open the gate!
                Thread.sleep(DELAY_MS);
                mServo.setAngle(mServo.getMinimumAngle()); // Close the gate!

            }
            catch (InterruptedException e) {
                Log.e(TAG, "Interrupted");
            }
            catch (IOException e) {
                Log.e(TAG, "Error setting Servo angle");
            }

        }

    };

    /**
     * Listener for new camera images.
     */
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireNextImage();
                    // get image bytes
                    ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                    final byte[] imageBytes = new byte[imageBuf.remaining()];
                    imageBuf.get(imageBytes);
                    image.close();

                    onPictureTaken(imageBytes);
                }
            };

    /**
     * Handle image processing in Firebase and Cloud Vision.
     */
    private void onPictureTaken(final byte[] imageBytes) {
        if (imageBytes != null) {
            mCloudHandler.post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "sending image to cloud vision");
                    // annotate image by uploading to Cloud Vision API
                    try {
                        Map<String, Float> annotations = CloudVisionUtils.annotateImage(imageBytes);
                        Log.d(TAG, "cloud vision annotations:" + annotations);
                        if (annotations != null) {

                            if (annotations.containsKey("car") || annotations.containsKey("vehicle")) {
                                mServoHandler.post(mOpenTheGates);
                                Log.d(TAG, "CAR DETECTED!!! OPEN THE GATES!");
                            }

                            //log.child("annotations").setValue(annotations);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Cloud Vison API error: ", e);
                    }
                }
            });
        }
    }


}
