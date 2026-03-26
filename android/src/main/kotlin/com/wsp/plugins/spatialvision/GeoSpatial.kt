package com.wsp.plugins.spatialvision
//
//import android.opengl.GLSurfaceView
//import android.os.Bundle
//import android.util.Log
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import android.widget.Button
//import androidx.fragment.app.Fragment
//import com.google.ar.core.Config
//import com.google.ar.core.Config.InstantPlacementMode
//import com.google.ar.core.Session
//import com.google.ar.core.exceptions.CameraNotAvailableException
//import com.google.ar.core.exceptions.UnavailableApkTooOldException
//import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
//import com.google.ar.core.exceptions.UnavailableSdkTooOldException
//import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
//import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
//import com.wsp.plugins.spatialvision.common.helpers.DepthSettings
//import com.wsp.plugins.spatialvision.common.helpers.InstantPlacementSettings
//import com.wsp.plugins.spatialvision.common.helpers.ARCoreSessionLifecycleHelper
//import com.wsp.plugins.spatialvision.helloar.BasicArRenderer
//import com.wsp.plugins.spatialvision.helloar.HelloArRenderer
//import com.wsp.plugins.spatialvision.helloar.SimpleArRenderer
//import com.wsp.plugins.spatialvision.helloar.HelloArView
//
//class GeoSpatial : Fragment() {
//    private var title: String? = ""
//
//    interface FragmentInteractionListener {
//        fun onCloseFragment()
//    }
//    private var listener: FragmentInteractionListener? = null
//    companion object {
//        private const val TAG = "HelloGeoActivity"
//    }
//    lateinit var originalView: View
//    lateinit var glSurfaceView: GLSurfaceView
//    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
//    lateinit var view: HelloArView
////    lateinit var renderer: SimpleArRenderer
//    lateinit var renderer: HelloArRenderer
////    lateinit var renderer: BasicArRenderer
//
//
//    private var sampleRender: SampleRender? = null
//
//    val instantPlacementSettings = InstantPlacementSettings()
//    val depthSettings = DepthSettings()
//
//    override fun onCreateView(
//        inflater: LayoutInflater, container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View? {
//        // Retrieve the location data from arguments
//        arguments?.let {
//            title = it.getString("title", "No title")
//        }
//        // Inflate the layout for this fragment
//        originalView = inflater.inflate(R.layout.spatial_vision, container, false)
//
//        // Initialize GLSurfaceView
//        glSurfaceView = originalView.findViewById(this.resources.getIdentifier("surfaceview", "id", originalView.context.packageName ))
//        glSurfaceView.preserveEGLContextOnPause = true
//        glSurfaceView.setEGLContextClientVersion(2)
//        initialSetup()
//        return originalView
//    }
//
//    fun setFragmentInteractionListener(listener: SpatialVisionPlugin){
//        this.listener = listener
//    }
//
//    private fun initialSetup(){
//        // Setup ARCore session lifecycle helper and configuration.
//        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
//        // If Session creation or Session.resume() fails, display a message and log detailed
//        // information.
//        arCoreSessionHelper.exceptionCallback =
//            { exception ->
//                val message =
//                    when (exception) {
//                        is UnavailableUserDeclinedInstallationException ->
//                            "Please install Google Play Services for AR"
//                        is UnavailableApkTooOldException -> "Please update ARCore"
//                        is UnavailableSdkTooOldException -> "Please update this app"
//                        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
//                        is CameraNotAvailableException -> "Camera not available. Try restarting the app."
//                        else -> "Failed to create AR session: $exception"
//                    }
//                Log.e(TAG, "ARCore threw an exception", exception)
//                if (::view.isInitialized) {
//                    view.snackbarHelper.showError(this, message)
//                }
//            }
//
//        // Configure session features.
//        arCoreSessionHelper.beforeSessionResume = ::configureSession
//        lifecycle.addObserver(arCoreSessionHelper)
//
//        // Set up the Hello AR renderer.
////        renderer = SimpleArRenderer(this)
////        renderer = BasicArRenderer(this)
//            renderer = HelloArRenderer(this)
//
//
//        lifecycle.addObserver(renderer)
//
//        // Set up Hello AR UI.
////        view = HelloArController(this, originalView)
//        view = HelloArView(this, originalView)
//        lifecycle.addObserver(view)
//
//        // Sets up an example renderer using our HelloGeoRenderer.
//        SampleRender(view.surfaceView, renderer, this.requireActivity().assets)
//
//        depthSettings.onCreate(this.requireContext())
//        instantPlacementSettings.onCreate(this.requireContext())
//
//        val closeButton: Button = originalView.findViewById(R.id.close_button)
//        closeButton.setOnClickListener({
////            arCoreSessionHelper.session?.close()
////            session = Session(requireContext())
//            listener?.onCloseFragment()
//        })
//    }
//
//    // Configure the session, setting the desired options according to your usecase.
//    fun configureSession(session: Session) {
//        session.configure(
//            session.config.apply {
//                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
//
//                // Depth API is used if it is configured in Hello AR's settings.
//                depthMode =
//                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                        Config.DepthMode.AUTOMATIC
//                    } else {
//                        Config.DepthMode.DISABLED
//                    }
//                // CRITICAL: Enable plane finding for better tracking stability
//                // Both horizontal and vertical planes help with object placement
//                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//
//                // Instant Placement is used if it is configured in Hello AR's settings.
//                instantPlacementMode =
//                    if (instantPlacementSettings.isInstantPlacementEnabled) {
//                        InstantPlacementMode.LOCAL_Y_UP
//                    } else {
//                        InstantPlacementMode.DISABLED
//                    }
//                // Enable focus mode for better tracking in low-feature areas
//                focusMode = Config.FocusMode.AUTO
//            }
//        )
//    }
//
//}