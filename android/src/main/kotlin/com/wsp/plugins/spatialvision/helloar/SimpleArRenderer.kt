package com.wsp.plugins.spatialvision.helloar
//
//import android.opengl.Matrix
//import android.util.Log
//import androidx.lifecycle.DefaultLifecycleObserver
//import androidx.lifecycle.LifecycleOwner
//import com.google.ar.core.*
//import com.google.ar.core.exceptions.CameraNotAvailableException
//import com.wsp.plugins.spatialvision.GeoSpatial
//import com.wsp.plugins.spatialvision.R
//import com.wsp.plugins.spatialvision.common.helpers.DisplayRotationHelper
//import com.wsp.plugins.spatialvision.common.helpers.TrackingStateHelper
//import com.wsp.plugins.spatialvision.common.samplerender.Framebuffer
//import com.wsp.plugins.spatialvision.common.samplerender.IndexBuffer
//import com.wsp.plugins.spatialvision.common.samplerender.Mesh
//import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
//import com.wsp.plugins.spatialvision.common.samplerender.Shader
//import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
//import com.wsp.plugins.spatialvision.common.samplerender.arcore.BackgroundRenderer
//import java.io.IOException
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//
///** Renders the HelloAR application with tap-to-place anchor functionality. */
//class SimpleArRenderer(val activity: GeoSpatial) :
//  SampleRender.Renderer, DefaultLifecycleObserver {
//
//  companion object {
//    val TAG = "SimpleArRenderer"
//    private val Z_NEAR = 0.1f
//    private val Z_FAR = 100f
//  }
//
//  lateinit var render: SampleRender
//  lateinit var backgroundRenderer: BackgroundRenderer
//  lateinit var virtualSceneFramebuffer: Framebuffer
//  var hasSetTextureNames = false
//  var isInitialized = false
//
//  // Virtual object components
//  private var virtualObjectMesh: Mesh? = null
//  private var virtualObjectShader: Shader? = null
//  private var virtualObjectVertexBuffer: VertexBuffer? = null
//
//  // Simple marker data (a small colored cube)
//  private val markerVertices = floatArrayOf(
//    // Front face
//    -0.05f, -0.05f,  0.05f,  // 0
//    0.05f, -0.05f,  0.05f,  // 1
//    0.05f,  0.05f,  0.05f,  // 2
//    -0.05f,  0.05f,  0.05f,  // 3
//    // Back face
//    -0.05f, -0.05f, -0.05f,  // 4
//    0.05f, -0.05f, -0.05f,  // 5
//    0.05f,  0.05f, -0.05f,  // 6
//    -0.05f,  0.05f, -0.05f   // 7
//  )
//
//  private val markerIndices = intArrayOf(
//    // Front face
//    0, 1, 2,
//    2, 3, 0,
//    // Back face
//    4, 5, 6,
//    6, 7, 4,
//    // Top face
//    3, 2, 6,
//    6, 7, 3,
//    // Bottom face
//    0, 1, 5,
//    5, 4, 0,
//    // Right face
//    1, 2, 6,
//    6, 5, 1,
//    // Left face
//    0, 3, 7,
//    7, 4, 0
//  )
//
//  private val anchors = mutableListOf<Anchor>()
//
//  // Temporary matrix allocated here to reduce number of allocations for each frame.
//  val modelMatrix = FloatArray(16)
//  val viewMatrix = FloatArray(16)
//  val projectionMatrix = FloatArray(16)
//  val modelViewMatrix = FloatArray(16)
//  val modelViewProjectionMatrix = FloatArray(16)
//
//  val session
//    get() = activity.arCoreSessionHelper.session
//
//  val displayRotationHelper = DisplayRotationHelper(activity.requireContext())
//  val trackingStateHelper = TrackingStateHelper(activity.requireActivity())
//
//  override fun onResume(owner: LifecycleOwner) {
//    displayRotationHelper.onResume()
//    hasSetTextureNames = false
//    isInitialized = false
//  }
//
//  override fun onPause(owner: LifecycleOwner) {
//    displayRotationHelper.onPause()
//  }
//
//  override fun onSurfaceCreated(render: SampleRender) {
//    this.render = render
//
//    try {
//      // Initialize background renderer
//      backgroundRenderer = BackgroundRenderer(render)
//      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)
//
//      // IMPORTANT: Initialize the background shaders
//      try {
//        // Initialize background shader (choose between camera view or depth visualization)
//        backgroundRenderer.setUseDepthVisualization(render, false) // false = show camera, true = show depth visualization
//
//        // Initialize occlusion shader (false = no occlusion)
//        backgroundRenderer.setUseOcclusion(render, false)
//
//        Log.d(TAG, "Background shaders initialized successfully")
//      } catch (e: IOException) {
//        Log.e(TAG, "Failed to initialize background shaders", e)
//        showError("Failed to initialize background shaders: $e")
//        isInitialized = false
//        return
//      }
//
//      // Create a simple colored marker using basic shaders
//      initializeMarker()
//
//      isInitialized = true
//      Log.d(TAG, "Renderer initialized successfully")
//
//    } catch (e: IOException) {
//      Log.e(TAG, "Failed to read a required asset file", e)
//      showError("Failed to read a required asset file: $e")
//      isInitialized = false
//    } catch (e: Exception) {
//      Log.e(TAG, "Failed to initialize renderer", e)
//      showError("Failed to initialize renderer: $e")
//      isInitialized = false
//    }
//  }
//
//  private fun initializeMarker() {
//    try {
//      // Create vertex buffer for marker
//      val vertexBufferData = ByteBuffer.allocateDirect(markerVertices.size * 4)
//        .order(ByteOrder.nativeOrder())
//        .asFloatBuffer()
//      vertexBufferData.put(markerVertices)
//      vertexBufferData.position(0)
//
//      virtualObjectVertexBuffer = VertexBuffer(
//        render,
//        /*numberOfEntriesPerVertex=*/ 3,  // x, y, z
//        vertexBufferData
//      )
//
//      // Create index buffer - Create IndexBuffer directly
//      val indexBufferData = ByteBuffer.allocateDirect(markerIndices.size * 4)
//        .order(ByteOrder.nativeOrder())
//        .asIntBuffer()
//      indexBufferData.put(markerIndices)
//      indexBufferData.position(0)
//
//      // FIX: Create IndexBuffer directly instead of VertexBuffer
//      val indexBuffer = IndexBuffer(render, indexBufferData)
//
//      // Create mesh - Pass indexBuffer as IndexBuffer
//      virtualObjectMesh = Mesh(
//        render,
//        Mesh.PrimitiveMode.TRIANGLES,
//        indexBuffer,
//        arrayOf(virtualObjectVertexBuffer!!)
//      )
//
//      // Create simple shader
//      val vertexShaderCode = """
//            uniform mat4 u_ModelViewProjection;
//            attribute vec4 a_Position;
//            void main() {
//                gl_Position = u_ModelViewProjection * a_Position;
//            }
//        """.trimIndent()
//
//      val fragmentShaderCode = """
//            precision mediump float;
//            uniform vec4 u_Color;
//            void main() {
//                gl_FragColor = u_Color;
//            }
//        """.trimIndent()
//
//      virtualObjectShader = Shader(render, vertexShaderCode, fragmentShaderCode, null)
//        .setVec4("u_Color", floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)) // Green color
//
//      Log.d(TAG, "Marker initialized successfully")
//
//    } catch (e: Exception) {
//      Log.e(TAG, "Failed to initialize marker", e)
//      throw e
//    }
//  }
//
//  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
//    displayRotationHelper.onSurfaceChanged(width, height)
//    if (::virtualSceneFramebuffer.isInitialized) {
//      virtualSceneFramebuffer.resize(width, height)
//    }
//  }
//
//  override fun onDrawFrame(render: SampleRender) {
//    if (!isInitialized) {
//      Log.w(TAG, "Renderer not initialized yet")
//      return
//    }
//
//    val session = session ?: return
//
//    // Texture names should only be set once on a GL thread unless they change
//    if (!hasSetTextureNames) {
//      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
//      hasSetTextureNames = true
//    }
//
//    // Update session with display rotation
//    displayRotationHelper.updateSessionIfNeeded(session)
//
//    // Obtain the current frame from ARSession
//    val frame = try {
//      session.update()
//    } catch (e: CameraNotAvailableException) {
//      Log.e(TAG, "Camera not available during onDrawFrame", e)
//      showError("Camera not available. Try restarting the app.")
//      return
//    }
//
//    val camera = frame.camera
//
//    // Update background display geometry
//    backgroundRenderer.updateDisplayGeometry(frame)
//
//    // Handle one tap per frame
//    handleTap(frame, camera)
//
//    // Update keep screen on flag
//    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)
//
//    // Show message based on tracking state
//    val message: String? = when {
//      camera.trackingState == TrackingState.PAUSED &&
//              camera.trackingFailureReason == TrackingFailureReason.NONE ->
//        activity.getString(R.string.searching_planes)
//      camera.trackingState == TrackingState.PAUSED ->
//        TrackingStateHelper.getTrackingFailureReasonString(camera)
//      anchors.isEmpty() ->
//        activity.getString(R.string.waiting_taps)
//      else -> null
//    }
//
//    if (message == null) {
//      activity.view.snackbarHelper.hide(activity.requireActivity())
//    } else {
//      activity.view.snackbarHelper.showMessage(activity.requireActivity(), message)
//    }
//
//    // Draw background
//    if (frame.timestamp != 0L) {
//      backgroundRenderer.drawBackground(render)
//    }
//
//    // If not tracking, don't draw 3D objects
//    if (camera.trackingState == TrackingState.PAUSED) {
//      return
//    }
//
//    // Get projection and view matrices
//    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
//    camera.getViewMatrix(viewMatrix, 0)
//
//    // Clear the virtual scene framebuffer
//    if (::virtualSceneFramebuffer.isInitialized) {
//      render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
//    }
//
//    // Draw all anchors
//    if (virtualObjectShader != null && virtualObjectMesh != null) {
//      for (anchor in anchors) {
//        if (anchor.trackingState == TrackingState.TRACKING) {
//          try {
//            // Get the current pose of the Anchor
//            anchor.pose.toMatrix(modelMatrix, 0)
//
//            // Calculate model/view/projection matrices
//            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
//            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
//
//            // Update shader properties and draw
//            virtualObjectShader!!.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
//
//            if (::virtualSceneFramebuffer.isInitialized) {
//              render.draw(virtualObjectMesh!!, virtualObjectShader!!, virtualSceneFramebuffer)
//            } else {
//              render.draw(virtualObjectMesh!!, virtualObjectShader!!)
//            }
//
//          } catch (e: Exception) {
//            Log.e(TAG, "Error drawing anchor", e)
//          }
//        }
//      }
//    } else {
//      Log.w(TAG, "Shader or mesh is null - cannot draw anchors")
//    }
//
//    // Compose the virtual scene with the background
//    if (::backgroundRenderer.isInitialized && ::virtualSceneFramebuffer.isInitialized) {
//      backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
//    }
//  }
//
//  // Handle tap to place anchor
//  private fun handleTap(frame: Frame, camera: Camera) {
//    if (camera.trackingState != TrackingState.TRACKING) return
//    val tap = activity.view.tapHelper.poll() ?: return
//
//    // Perform hit test
//    val hitResultList = frame.hitTest(tap)
//
//    // Find the best hit result (plane, point, or any trackable)
//    val firstHitResult = hitResultList.firstOrNull { hit ->
//      when (val trackable = hit.trackable) {
//        is Plane -> {
//          trackable.isPoseInPolygon(hit.hitPose) &&
//                  calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
//        }
//        is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
//        is DepthPoint -> true
//        else -> false
//      }
//    }
//
//    if (firstHitResult != null) {
//      // Limit number of anchors to avoid performance issues
//      if (anchors.size >= 2) {
//        anchors[0].detach()
//        anchors.removeAt(0)
//      }
//
//      // Create anchor at hit location
//      val anchor = firstHitResult.createAnchor()
//      anchors.add(anchor)
//
//      Log.d(TAG, "Anchor placed at: ${anchor.pose.ty()}, ${anchor.pose.tx()}, ${anchor.pose.tz()}")
//
//      // Show success message
//      activity.view.snackbarHelper.showMessage(
//        activity.requireActivity(),
//        "Anchor placed at hit location"
//      )
//    }
//  }
//
//  // Helper method to calculate distance to plane
//  private fun calculateDistanceToPlane(hitPose: Pose, cameraPose: Pose): Float {
//    val hitPosition = hitPose.translation
//    val cameraPosition = cameraPose.translation
//    val hitNormal = hitPose.qy() // Simplified - you might want proper plane distance calculation
//    return (cameraPosition[1] - hitPosition[1]) // Simple height difference
//  }
//
//  private fun showError(errorMessage: String) {
//    activity.view.snackbarHelper.showError(activity, errorMessage)
//  }
//
//  // Clean up anchors when done
//  fun clearAnchors() {
//    anchors.forEach { it.detach() }
//    anchors.clear()
//    Log.d(TAG, "Cleared all anchors")
//  }
//}