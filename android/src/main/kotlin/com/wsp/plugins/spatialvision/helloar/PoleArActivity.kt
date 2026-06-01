package com.wsp.plugins.spatialvision.helloar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.Session
import com.wsp.plugins.spatialvision.common.helpers.CameraPermissionHelper
import com.wsp.plugins.spatialvision.common.helpers.DepthSettings
import com.wsp.plugins.spatialvision.common.helpers.FullScreenHelper
import com.wsp.plugins.spatialvision.common.helpers.InstantPlacementSettings
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.wsp.plugins.spatialvision.R

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
class PoleArActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PoleArActivity"
    }

    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    lateinit var view: PoleArView
    lateinit var renderer: HelloArRenderer
    lateinit var sceneManager: SceneManager;

    val instantPlacementSettings = InstantPlacementSettings()
    val depthSettings = DepthSettings()
    private var pendingResult = false


//    lateinit var distanceObjToObj: TextView
//    lateinit var distanceCamToObj1: TextView
//    lateinit var distanceCamToObj2: TextView
//    lateinit var depthConfidence: TextView


    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup ARCore session lifecycle helper and configuration.
        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
        // If Session creation or Session.resume() fails, display a message and log detailed
        // information.
        arCoreSessionHelper.exceptionCallback =
            { exception ->
                val message =
                    when (exception) {
                        is UnavailableUserDeclinedInstallationException ->
                            "Please install Google Play Services for AR"
                        is UnavailableApkTooOldException -> "Please update ARCore"
                        is UnavailableSdkTooOldException -> "Please update this app"
                        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                        is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                        else -> "Failed to create AR session: $exception"
                    }
                Log.e(TAG, "ARCore threw an exception", exception)
                view.snackbarHelper.showError(this, message)
            }

        // Configure session features, including: Lighting Estimation, Depth mode, Instant Placement.
        arCoreSessionHelper.beforeSessionResume = ::configureSession
        lifecycle.addObserver(arCoreSessionHelper)
        val cylinder = Cylinder()
        // 1. Core systems
        sceneManager = SceneManager(cylinder)

        // Set up Hello AR UI.
        view = PoleArView(this)
        lifecycle.addObserver(view)
        setContentView(view.root)

        view.onExportRequested = {
            triggerExport()
        }
        view.onPhaseConfigChange = { config ->
            sceneManager.currentConfig = config
            view.updateMenuCheckedStates(config)
        }

        // Set up the Hello AR renderer.
        renderer = HelloArRenderer(this)
        lifecycle.addObserver(renderer)
        setupCallbacks()

        // Sets up an example renderer using our HelloARRenderer.
        SampleRender(view.surfaceView, renderer, assets)

        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)

        // 👇 Add this (find button from layout)
        val closeButton: Button = findViewById<Button>(R.id.close_button)
//        distanceObjToObj = findViewById(R.id.distance_between_objects)
//        distanceCamToObj1 = findViewById(R.id.distance_cam_obj1)
//        distanceCamToObj2 = findViewById(R.id.distance_cam_obj2)
//        depthConfidence = findViewById(R.id.depth_confidence)

        closeButton.setOnClickListener {
            sendResultAndFinish()
        }
        view.doneBtn.setOnClickListener {
            pendingResult = true
            renderer.captureBtnStatus = false
            view.snackbarHelper.showMessage(this, "Please wait while capturing image")
            // 🔥 Trigger capture in GL thread
            renderer.captureHighRes = true
        }
        renderer.onImageCaptured = { bitmap ->
            runOnUiThread {
                val uri = view.saveBitmap(this, bitmap)
//                val uri = view.saveBitmapToFile(this, bitmap)
                view.snackbarHelper.showMessage(this, "image captured")
                if(!renderer.captureBtnStatus){
                    val resultIntent = Intent().apply {
                        putExtra("status", 200)
                        putExtra("imagePath", uri.toString())
                        putExtra("measurements", ArrayList(view.measurements))
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }else{
                    renderer.captureBtnStatus = false
                }
            }
        }
    }

    private fun setupCallbacks() {
        view.callbacks = object : HelloArView.MeasurementCallbacks {
            override fun onReset() {
                renderer.
                resetMeasurements()
                view.resetUI()
            }

            override fun onUndo() {
                renderer.undoMeasurements()
                view.snackbarHelper.showMessage(view.activity, "Last measurement cleared")
            }

        }
    }

    // Configure the session, using Lighting Estimation, and Depth mode.
    fun configureSession(session: Session) {
        session.configure(
            session.config.apply {
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

                // Depth API is used if it is configured in Hello AR's settings.
                depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }

                // Instant Placement is used if it is configured in Hello AR's settings.
                instantPlacementMode =
                    if (instantPlacementSettings.isInstantPlacementEnabled) {
                        InstantPlacementMode.LOCAL_Y_UP
                    } else {
                        InstantPlacementMode.DISABLED
                    }
            }
        )
    }

    @SuppressLint("SetTextI18n")
    fun updateDistances(objToObj: Float?, camToObj1: Float?, camToObj2: Float?, confidence: Int?) {
        runOnUiThread {
//            distanceObjToObj.text = "Obj1 ↔ Obj2: ${format(objToObj)} m"
//            distanceCamToObj1.text = "Camera → Obj1: ${format(camToObj1)} m"
//            distanceCamToObj2.text = "Camera → Obj2: ${format(camToObj2)} m"
//            depthConfidence.text = "Confidence: $confidence"
//            if (confidence != null && confidence > 0) {
//                depthConfidence.visibility = View.VISIBLE
//                depthConfidence.text = "Confidence: $confidence"
//            } else {
//                depthConfidence.visibility = View.GONE
//            }

        }
    }

    private fun format(value: Float?): String {
        return if (value == null) "--" else String.format("%.2f", value)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun triggerExport() {

        Thread {

            try {
                val glb = renderer.exportSceneGLB()

                val uri = view.saveObjToDownloadsModern(this, glb)

                runOnUiThread {
                    Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }

        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onBackPressed() {
        sendResultAndFinish()
    }

    private fun sendResultAndFinish() {
        val resultIntent = Intent()
        // Send any data you want back
        resultIntent.putExtra("status", 200);

        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
