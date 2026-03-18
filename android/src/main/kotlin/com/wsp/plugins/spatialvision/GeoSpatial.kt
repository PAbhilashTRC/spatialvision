package com.wsp.plugins.spatialvision

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.wsp.plugins.spatialvision.examples.java.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.hellogeospatial.HelloGeoRenderer
import com.wsp.plugins.spatialvision.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.wsp.plugins.spatialvision.hellogeospatial.helpers.HelloGeoView

class GeoSpatial : Fragment() {
    private var title: String? = ""

    interface FragmentInteractionListener {
        fun onCloseFragment()
    }
    private var listener: FragmentInteractionListener? = null
    companion object {
        private const val TAG = "HelloGeoActivity"
    }
    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    lateinit var view: HelloGeoView
    lateinit var originalView: View
    lateinit var renderer: HelloGeoRenderer

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Retrieve the location data from arguments
        arguments?.let {
            title = it.getString("title", "No title")
        }
        // Inflate the layout for this fragment
        originalView = inflater.inflate(R.layout.spatial_vision, container, false)
        initialSetup()
//        val closeButton: Button = originalView.findViewById(R.id.close_button)
//        closeButton.setOnClickListener({
//            listener?.onCloseFragment()
//        })
        return originalView
    }

    fun setFragmentInteractionListener(listener: SpatialVisionPlugin){
        this.listener = listener
    }

    private fun initialSetup(){
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

        // Configure session features.
        arCoreSessionHelper.beforeSessionResume = ::configureSession
        lifecycle.addObserver(arCoreSessionHelper)

        // Set up the Hello AR renderer.
        renderer = HelloGeoRenderer(this)
        lifecycle.addObserver(renderer)

        // Set up Hello AR UI.
        view = HelloGeoView(this)

        lifecycle.addObserver(view)

        // Sets up an example renderer using our HelloGeoRenderer.
        SampleRender(view.surfaceView, renderer, this.requireActivity().assets)

        val closeButton: Button = originalView.findViewById(R.id.close_button)
        closeButton.setOnClickListener({
            listener?.onCloseFragment()
        })
    }

    // Configure the session, setting the desired options according to your usecase.
    fun configureSession(session: Session) {
        session.configure(session.config.apply { geospatialMode = Config.GeospatialMode.ENABLED })

    }

}