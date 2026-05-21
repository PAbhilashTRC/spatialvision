package com.wsp.plugins.spatialvision

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.wsp.plugins.spatialvision.helloar.HelloArActivity
import com.wsp.plugins.spatialvision.helloar.Measurement

@CapacitorPlugin(
    name = "SpatialVision",
    permissions = [
        Permission(
            alias = "camera",
            strings = [Manifest.permission.CAMERA]
        )
    ]
)
//class SpatialVisionPlugin: Plugin(), GeoSpatial.FragmentInteractionListener {
class SpatialVisionPlugin: Plugin() {
    private var activeCall: PluginCall? = null
//    private lateinit var cameraFragment: GeoSpatial;

    private var fragmentContainer: FrameLayout? = null

    @PluginMethod
    fun startCamera(call: PluginCall) {
        this.activeCall = call

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionCallback")
        } else {
//            addFragmentContainer()
            startArActivity(call)
        }
    }

    @PluginMethod
    fun poleDigitalTwin(call: PluginCall) {
        this.activeCall = call

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionCallback")
        } else {
//            addFragmentContainer()
            startArActivity(call)
        }
    }

    @PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
//            addFragmentContainer()
            startArActivity(call)
        } else {
            call.reject("Camera permission denied")
        }
    }

    fun startArActivity( call: PluginCall){

        // Create an intent to launch your custom activity
        val intent: Intent = Intent(activity, HelloArActivity::class.java)


        // Optional: Pass data to the new activity using extras
        intent.putExtra("title", call.getString("title")?: "Hello AR")


        // Check if you need a result back
        val expectResult: Boolean = call.getBoolean("expectResult", true)?: true

        if (expectResult) {
            // Launch the activity and expect a result back in the "handleActivityResult" method
            startActivityForResult(call, intent, "handleActivityResult")
        } else {
            // Launch the activity without expecting a result
            activity.startActivity(intent)
            call.resolve()
        }
    }

    @ActivityCallback
    private fun handleActivityResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) {
            return
        }

        // Handle the result data from the finished activity
        val resultCode = result.resultCode
        val data = result.data

        if (resultCode == Activity.RESULT_OK && data != null) {
            // Process the result and resolve the plugin call
            val resultData = data.getIntExtra("status", 500)
            val imagePath = data.getStringExtra("imagePath")
            val measurements = data.getSerializableExtra("measurements") as? ArrayList<Measurement>
            val ret = JSObject()
            ret.put("status", resultData)
            ret.put("imagePath", imagePath)
            val measurementsArray = JSArray()

            measurements?.forEach { m ->
                val obj = JSObject()
                obj.put("label", m.label)
                obj.put("distance", m.distance)
                obj.put("unit", m.unit)
                obj.put("startPoint", m.startPoint)
                obj.put("endPoint", m.endPoint)
                measurementsArray.put(obj)
            }
            ret.put("measurements", measurementsArray)
            if(resultData == 200){
                Toast.makeText(context, "Done", Toast.LENGTH_SHORT).show()
            }else{
                Toast.makeText(context, "Something went wrong", Toast.LENGTH_SHORT).show()
            }
            call.resolve(ret)
        } else {
            // Activity was canceled or failed
            call.reject("Activity cancelled or failed")
        }
    }

//    fun addFragmentContainer() {
//        activity.runOnUiThread {
//
//            val container = FrameLayout(activity)
//            container.id = View.generateViewId()
//
//            fragmentContainer = container
//
//            activity.addContentView(
//                container,
//                FrameLayout.LayoutParams(
//                    FrameLayout.LayoutParams.MATCH_PARENT,
//                    FrameLayout.LayoutParams.MATCH_PARENT
//                )
//            )
//
//            container.bringToFront()
//
//            cameraFragment = GeoSpatial()
//            cameraFragment.setFragmentInteractionListener(this@SpatialVisionPlugin)
//
//            val transaction = activity.supportFragmentManager.beginTransaction()
//            transaction.replace(container.id, cameraFragment)
//            transaction.addToBackStack(null)
//            transaction.commit()
//        }
//    }
//
//    override fun onCloseFragment() {
//        activity.runOnUiThread {
//            val fragmentManager = activity.supportFragmentManager
//
//            cameraFragment.let {
//                fragmentManager.beginTransaction().remove(it).commit()
//            }
//
//            fragmentContainer?.let {
//                (it.parent as? FrameLayout)?.removeView(it)
//                fragmentContainer = null
//            }
//
//            val ret = JSObject().apply {
//                put("result", "Fragment closed")
//            }
//
//            activeCall?.resolve(ret)
//            activeCall = null
//        }
//    }


}
