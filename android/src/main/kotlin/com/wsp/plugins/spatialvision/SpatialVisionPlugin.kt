package com.wsp.plugins.spatialvision

//noinspection SuspiciousImport
import android.Manifest
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "SpatialVision",
    permissions = [
        Permission(
            alias = "camera",
            strings = [Manifest.permission.CAMERA]
        )
    ]
)
class SpatialVisionPlugin: Plugin(), GeoSpatial.FragmentInteractionListener {
    private val implementation = SpatialVision()
    private val cameraAccess = CameraPermissionHelper
    private var activeCall: PluginCall? = null
    private lateinit var cameraFragment: GeoSpatial;

    private var fragmentContainer: FrameLayout? = null

    @PluginMethod
    fun echo(call: PluginCall) {
        val value = call.getString("value")

        val ret = JSObject().apply {
            put("value", implementation.echo(value))
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun startCamera(call: PluginCall) {
        this.activeCall = call

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionCallback")
        } else {
            addFragmentContainer()
        }
    }

    @PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            addFragmentContainer()
        } else {
            call.reject("Camera permission denied")
        }
    }

    fun addFragmentContainer() {
        activity.runOnUiThread {

            val container = FrameLayout(activity)
            container.id = View.generateViewId()

            fragmentContainer = container

            activity.addContentView(
                container,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            container.bringToFront()

            cameraFragment = GeoSpatial()
            cameraFragment.setFragmentInteractionListener(this@SpatialVisionPlugin)

            val transaction = activity.supportFragmentManager.beginTransaction()
            transaction.replace(container.id, cameraFragment)
            transaction.addToBackStack(null)
            transaction.commit()
        }
    }

    override fun onCloseFragment() {
        activity.runOnUiThread {
            val fragmentManager = activity.supportFragmentManager

            cameraFragment.let {
                fragmentManager.beginTransaction().remove(it).commit()
            }

            fragmentContainer?.let {
                (it.parent as? FrameLayout)?.removeView(it)
                fragmentContainer = null
            }

            val ret = JSObject().apply {
                put("result", "Fragment closed")
            }

            activeCall?.resolve(ret)
            activeCall = null
        }
    }


}
