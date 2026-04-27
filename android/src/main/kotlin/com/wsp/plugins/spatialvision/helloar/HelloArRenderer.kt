package com.wsp.plugins.spatialvision.helloar

//import com.wsp.plugins.spatialvision.common.samplerender.arcore.PlaneRenderer
//import com.wsp.plugins.spatialvision.GeoSpatial
import ARSimpleCapture
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.wsp.plugins.spatialvision.common.helpers.ARLabelData
import com.wsp.plugins.spatialvision.common.helpers.DisplayRotationHelper
import com.wsp.plugins.spatialvision.common.helpers.TrackingStateHelper
import com.wsp.plugins.spatialvision.common.samplerender.Framebuffer
import com.wsp.plugins.spatialvision.common.samplerender.GLError
import com.wsp.plugins.spatialvision.common.samplerender.Mesh
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.common.samplerender.Shader
import com.wsp.plugins.spatialvision.common.samplerender.Texture
import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
import com.wsp.plugins.spatialvision.common.samplerender.arcore.BackgroundRenderer
import com.wsp.plugins.spatialvision.common.samplerender.arcore.SpecularCubemapFilter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.Timer
import java.util.TimerTask

/** Renders the HelloAR application using our example Renderer. */
class HelloArRenderer(val activity: HelloArActivity) :
    SampleRender.Renderer, DefaultLifecycleObserver {
    companion object {
        val TAG = "HelloArRenderer"

        // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
        // constants.
        private val sphericalHarmonicFactors =
            floatArrayOf(
                0.282095f,
                -0.325735f,
                0.325735f,
                -0.325735f,
                0.273137f,
                -0.273137f,
                0.078848f,
                -0.273137f,
                0.136569f
            )

        private val Z_NEAR = 0.1f
        private val Z_FAR = 100f

        val CUBEMAP_RESOLUTION = 16
        val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
    }

    lateinit var render: SampleRender
    lateinit var backgroundRenderer: BackgroundRenderer
    lateinit var virtualSceneFramebuffer: Framebuffer
    var hasSetTextureNames = false

    // Point Cloud
    lateinit var pointCloudVertexBuffer: VertexBuffer
    lateinit var pointCloudMesh: Mesh
    lateinit var pointCloudShader: Shader

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
    var lastPointCloudTimestamp: Long = 0

    // Virtual object (ARCore pawn)
    lateinit var virtualObjectMesh: Mesh
    lateinit var virtualObjectShader: Shader

    lateinit var virtualObjectAlbedoTexture: Texture
    lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

    private val wrappedAnchors = mutableListOf<WrappedAnchor>()

    // Environmental HDR
    lateinit var dfgTexture: Texture
    lateinit var cubemapFilter: SpecularCubemapFilter

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)

    val labelViewProjectionMatrix  = FloatArray(16) // view x projection

    private lateinit var labelRenderer: LabelRender

//    private var cylinder: Cylinder? = null
    private lateinit var cylinder: Cylinder

    val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

    val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
    val viewInverseMatrix = FloatArray(16)
    val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    val viewLightDirection = FloatArray(4) // view x world light direction

    val session
        get() = activity.arCoreSessionHelper.session

    val displayRotationHelper = DisplayRotationHelper(activity)
    val trackingStateHelper = TrackingStateHelper(activity)

    var currentMode = InteractionMode.NONE
    var selectedAnchor: WrappedAnchor? = null

    private var lastUpdateTime = 0L
    private lateinit var sceneManager: SceneManager
    private lateinit var tapHandler: TapHandler

    @Volatile
    var captureHighRes = false

    var captureWidth = 1920   // or 4096 if device supports
    var captureHeight = 1080

    private var viewportWidth = 1
    private var viewportHeight = 1
//    private val captureHelper = ARCaptureHelper(viewportWidth, viewportHeight)

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper.onResume()
        hasSetTextureNames = false
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper.onPause()
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects.
        // This involves reading shaders and 3D model files, so may throw an IOException.
        try {
//            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

            cubemapFilter =
                SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
            // Load environmental lighting values lookup table
            dfgTexture =
                Texture(
                    render,
                    Texture.Target.TEXTURE_2D,
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    /*useMipmaps=*/ false
                )
            // The dfg.raw file is a raw half-float texture with two channels.
            val dfgResolution = 64
            val dfgChannels = 2
            val halfFloatSize = 2

            val buffer: ByteBuffer =
                ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
            activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

            // SampleRender abstraction leaks here.
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
            GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                /*level=*/ 0,
                GLES30.GL_RG16F,
                /*width=*/ dfgResolution,
                /*height=*/ dfgResolution,
                /*border=*/ 0,
                GLES30.GL_RG,
                GLES30.GL_HALF_FLOAT,
                buffer
            )
            GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

            // Point cloud
            pointCloudShader =
                Shader.createFromAssets(
                    render,
                    "shaders/point_cloud.vert",
                    "shaders/point_cloud.frag",
                    /*defines=*/ null
                )
                    .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
                    .setFloat("u_PointSize", 5.0f)

            // four entries per vertex: X, Y, Z, confidence
            pointCloudVertexBuffer =
                VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
            val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer)
            pointCloudMesh =
                Mesh(render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers)

            // Virtual object to render (ARCore pawn)
            virtualObjectAlbedoTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_albedo.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )

            virtualObjectAlbedoInstantPlacementTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_albedo_instant_placement.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )

            val virtualObjectPbrTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_roughness_metallic_ao.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.LINEAR
                )
//            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn_ring4.obj")
//            virtualObjectMesh = Mesh.createFromAsset(render, "models/pole.obj")
            virtualObjectShader =
                Shader.createFromAssets(
                    render,
                    "shaders/environmental_hdr.vert",
                    "shaders/environmental_hdr.frag",
                    mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
                )
                    .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                    .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
                    .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
                    .setTexture("u_DfgTexture", dfgTexture)

            cylinder = Cylinder().also{
                it.onSurfaceCreated(render)
            }
            labelRenderer = LabelRender().also {
                it.onSurfaceCreated(render)
            }

            sceneManager = SceneManager(cylinder)
            tapHandler = TapHandler(sceneManager)

            activity.view.captureBtn.setOnClickListener {
                activity.view.surfaceView.queueEvent {
                    captureHighRes = true
                  }
            }

//            activity.view.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

//                @SuppressLint("SetTextI18n")
//                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                    val minRadius = 0.01f   // 1 cm
//                    val maxRadius = 0.5f    // 50 cm
//
//                    val t = progress / 100f
//                    val radiusMeters = minRadius + t * (maxRadius - minRadius) // 0.01 + ( progress/100) * (0.5-0.01)
//                    val radiusCentimeters = minRadius + progress * (maxRadius - minRadius) // 0.01 + ( progress/100) * (0.5-0.01)
//                    activity.view.pipeRadius.text = "Radius: $radiusCentimeters cm"
//                    activity.view.slider_value.text = "Slider Value : $progress"
//                val radius = progress / 1000f  // scale factor
//                    cylinder?.setRadius(radiusMeters)
//                }

//                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//
//                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//            })
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        virtualSceneFramebuffer.resize(width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(render: SampleRender) {
        val session = session ?: return

        // --- Setup camera texture ---
        if (!hasSetTextureNames) {
            session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        displayRotationHelper.updateSessionIfNeeded(session)

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            showError("Camera not available. Try restarting.")
            return
        }

        val camera = frame.camera

        // --- Decide resolution (NORMAL vs HIGH-RES) ---
        val isCapturing = captureHighRes
        val targetWidth = if (isCapturing) captureWidth else viewportWidth
        val targetHeight = if (isCapturing) captureHeight else viewportHeight

        // ✅ Resize framebuffer
        virtualSceneFramebuffer.resize(targetWidth, targetHeight)

        // ✅ Set viewport
//        render.setViewport(0, 0, targetWidth, targetHeight)

        // --- Background / Depth ---
        try {
            backgroundRenderer.setUseDepthVisualization(
                render,
                activity.depthSettings.depthColorVisualizationEnabled()
            )
            backgroundRenderer.setUseOcclusion(
                render,
                activity.depthSettings.useDepthForOcclusion()
            )
        } catch (e: IOException) {
            Log.e(TAG, "Assets error", e)
            return
        }

        backgroundRenderer.updateDisplayGeometry(frame)

        if (camera.trackingState == TrackingState.TRACKING &&
            (activity.depthSettings.useDepthForOcclusion() ||
                    activity.depthSettings.depthColorVisualizationEnabled())
        ) {
            try {
                val depthImage = frame.acquireDepthImage16Bits()
                backgroundRenderer.updateCameraDepthTexture(depthImage)
                depthImage.close()
            } catch (_: NotYetAvailableException) {}
        }

        // --- Input ---
        handleTap(frame, camera)
//        handleDrag(frame, camera)

        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

        // --- Draw background ---
        if (frame.timestamp != 0L) {
            backgroundRenderer.drawBackground(render)
        }

        if (camera.trackingState == TrackingState.PAUSED) return

        // --- Matrices ---
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
        camera.getViewMatrix(viewMatrix, 0)

        val cameraPose = camera.pose

        // --- Point Cloud ---
        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.timestamp > lastPointCloudTimestamp) {
                pointCloudVertexBuffer.set(pointCloud.points)
                lastPointCloudTimestamp = pointCloud.timestamp
            }
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(pointCloudMesh, pointCloudShader)
        }

        // --- Lighting ---
        updateLightEstimation(frame.lightEstimate, viewMatrix)

        // --- Virtual Scene ---
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        Matrix.multiplyMM(labelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // ============================================================
        // 🔥 DRAW POLES + DISTANCES
        // ============================================================
        for (pole in sceneManager.poles) {

            val start = pole.base.position   // world coords
            val end = pole.top.position

            val pipeLabelPose = calculateMidValueForLabel(start, end)
            val pipeLabelData = labelRenderer.calculateDistance(start, end)
            // --- Draw pole (cylinder) ---
            cylinder.draw(render, start, end, viewMatrix, projectionMatrix,
                uColor = floatArrayOf(0.68f, 0.62f, 0.40f, 1.0f),
                radius = 0.05f,
                asset = "Pole")

            // --- Distance label (Pole height) ---
            labelRenderer.draw(
                render = render,
                viewProjectionMatrix = labelViewProjectionMatrix,
                pose =pipeLabelPose,
                cameraPose = camera.displayOrientedPose,
                data = pipeLabelData,
                showCard = activity.view.showCardLabel
            )

            // ========================================================
            // 🔥 CROSS ARMS
            // ========================================================
            pole.crossArms.forEach { arm ->
                cylinder.draw(render, arm.localStart, arm.localEnd,
                    viewMatrix, projectionMatrix,
                    uColor = floatArrayOf(0.45f, 0.35f, 0.25f, 1.0f),
                    radius = 0.02f,
                    asset = "Crass_arms")
                val labelData = labelRenderer.calculateDistance(
                    start = arm.localStart,
                    end = arm.localEnd,
                )
                val crassArmPose = calculateMidValueForLabel(arm.localStart, arm.localEnd)
                labelRenderer.draw(
                    render = render,
                    viewProjectionMatrix = labelViewProjectionMatrix,
                    pose =crassArmPose,
                    cameraPose = camera.displayOrientedPose,
                    data = labelData,
                    showCard = activity.view.showCardLabel
                    )

                // ========================================================
                // 🔥 Insulators
                // ========================================================
                // Get pole direction for this pole
                val poleDir = sceneManager.getPoleDirection(pole)

                val insulatorBasePoints = sceneManager.getInsulatorBasePoints(arm)

                insulatorBasePoints.forEachIndexed { index, basePoint ->
                    // Calculate proper insulator direction based on configuration
                    val insulatorDir = sceneManager.getInsulatorDirection(arm, poleDir, basePoint, index)

                    cylinder.drawInsulator(
                        render,
                        basePoint,
                        insulatorDir,
                        viewMatrix,
                        projectionMatrix,
                        floatArrayOf(0.92f, 0.93f, 0.95f, 1.0f),
                        asset = "Insulators"
                    )
                }
            }
        }

        // ============================================================
        // 🔥 WIRES
        // ============================================================
        for (wire in sceneManager.wires) {

            if (wire.points.size < 2) continue

            var length = 0f

            for (i in 0 until wire.points.size - 1) {
                val p1 = wire.points[i]
                val p2 = wire.points[i + 1]

                cylinder.draw(
                    render,
                    p1,
                    p2,
                    viewMatrix,
                    projectionMatrix,
                    floatArrayOf(0.50f, 0.52f, 0.54f, 1.0f),
                    radius = 0.008f,
                    asset = "wires"
                )

                length += MathUtils.distance(p1, p2)
            }

            // ✅ SAFE midpoint
            val wirePose = calculateMidLabelPose(wire.points)

            val labelText = String.format("%.2f m", length)

            val wireLabel = ARLabelData(
                title = "Measuring Tool",
                measurement = labelText,
                sourceA = "",
                sourceB = "",
                confidence = "unknown"
            )

            labelRenderer.draw(
                render = render,
                viewProjectionMatrix = labelViewProjectionMatrix,
                pose = wirePose,
                cameraPose = camera.displayOrientedPose,
                data = wireLabel,
                showCard = activity.view.showCardLabel
            )
        }

        // --- Compose final scene ---
        backgroundRenderer.drawVirtualScene(
            render,
            virtualSceneFramebuffer,
            Z_NEAR,
            Z_FAR
        )

        // ============================================================
        // 📸 HIGH-RES CAPTURE
        // ============================================================
        // Simple direct capture (RECOMMENDED for your use case)
        if (captureHighRes) {
            captureHighRes = false

            try {
                // Give GPU a moment to finish
                GLES30.glFinish()

                val simpleCapture = ARSimpleCapture()
                val bitmap = simpleCapture.captureScreen(viewportWidth, viewportHeight)
                activity.view.saveBitmap(context = this.activity, bitmap = bitmap)
            } catch (e: Exception) {
                Log.e("Capture", "Failed to capture: ${e.message}")
            }

            virtualSceneFramebuffer.resize(viewportWidth, viewportHeight)
        }
    }

    /** Checks if we detected at least one plane. */
    private fun Session.hasTrackingPlane() =
        getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

    /** Update state based on the current frame's light estimation. */
    private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
        if (lightEstimate.state != LightEstimate.State.VALID) {
            virtualObjectShader.setBool("u_LightEstimateIsValid", false)
            return
        }
        virtualObjectShader.setBool("u_LightEstimateIsValid", true)
        Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
        virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
        updateMainLight(
            lightEstimate.environmentalHdrMainLightDirection,
            lightEstimate.environmentalHdrMainLightIntensity,
            viewMatrix
        )
        updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
        cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
    }

    private fun updateMainLight(
        direction: FloatArray,
        intensity: FloatArray,
        viewMatrix: FloatArray
    ) {
        // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
        worldLightDirection[0] = direction[0]
        worldLightDirection[1] = direction[1]
        worldLightDirection[2] = direction[2]
        Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
        virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
        virtualObjectShader.setVec3("u_LightIntensity", intensity)
    }

    private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
        // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
        // constants in sphericalHarmonicFactors were derived from three terms:
        //
        // 1. The normalized spherical harmonics basis functions (y_lm)
        //
        // 2. The lambertian diffuse BRDF factor (1/pi)
        //
        // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
        // of all incoming light over a hemisphere for a given surface normal, which is what the shader
        // (environmental_hdr.frag) expects.
        //
        // You can read more details about the math here:
        // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
        require(coefficients.size == 9 * 3) {
            "The given coefficients array must be of length 27 (3 components per 9 coefficients"
        }

        // Apply each factor to every component of each coefficient
        for (i in 0 until 9 * 3) {
            sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
        }
        virtualObjectShader.setVec3Array(
            "u_SphericalHarmonicsCoefficients",
            sphericalHarmonicsCoefficients
        )
    }

    fun calculateMidValueForLabel(start: Vec3, end: Vec3): Pose{
        val up = FloatArray(3)
        val midpoint = MathUtils.getMidPoint(start, end)
        midpoint[0] += up[0] * 0.05f
        midpoint[1] += up[1] * 0.05f
        midpoint[2] += up[2] * 0.05f
        val labelPose = Pose(midpoint, floatArrayOf(0f, 0f, 0f, 1f))
        return labelPose
    }

    fun calculateMidLabelPose(
        wirePoints: List<Vec3>
    ): Pose {
        if (wirePoints.size < 2) {
            return Pose(floatArrayOf(0f,0f,0f), floatArrayOf(0f,0f,0f,1f))
        }
        val mid = getWireMidPoint(wirePoints)

        val up = floatArrayOf(0f, 1f, 0f) // world up

        val offset = 0.05f

        val labelPos = floatArrayOf(
            mid.x + up[0] * offset,
            mid.y + up[1] * offset,
            mid.z + up[2] * offset
        )

        return Pose(labelPos, floatArrayOf(0f, 0f, 0f, 1f))
    }

    fun getWireMidPoint(points: List<Vec3>): Vec3 {

        var totalLength = 0f
        val segmentLengths = FloatArray(points.size - 1)

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val dz = p2.z - p1.z

            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            segmentLengths[i] = len
            totalLength += len
        }

        val half = totalLength / 2f
        var acc = 0f

        for (i in segmentLengths.indices) {
            val next = acc + segmentLengths[i]

            if (next >= half) {
                val t = (half - acc) / segmentLengths[i]

                val p1 = points[i]
                val p2 = points[i + 1]

                return Vec3(
                    p1.x + (p2.x - p1.x) * t,
                    p1.y + (p2.y - p1.y) * t,
                    p1.z + (p2.z - p1.z) * t
                )
            }

            acc = next
        }

        // fallback (center point if something goes wrong)
        return points[points.size / 2]
    }

    fun exportSceneGLB(): ByteArray {
        val exporter = GlbExporter()

        // Add materials first
        val material_pole = exporter.addMaterial(
            floatArrayOf(0.45f, 0.35f, 0.25f, 1.0f),  // Wood/Concrete color
            0.2f,  // metallic
            0.8f   // roughness
        )

        val material_arm = exporter.addMaterial(
            floatArrayOf(0.55f, 0.45f, 0.35f, 1.0f),
            0.3f,
            0.7f
        )

        val material_insulator = exporter.addMaterial(
            floatArrayOf(0.9f, 0.9f, 0.95f, 1.0f),  // Ceramic/Glass
            0.1f,
            0.4f
        )

        val material_bolt = exporter.addMaterial(
            floatArrayOf(0.7f, 0.7f, 0.7f, 1.0f),   // Silver/Metal
            0.8f,
            0.3f
        )

        val material_wire = exporter.addMaterial(
            floatArrayOf(0.3f, 0.3f, 0.3f, 1.0f),   // Dark gray
            0.7f,
            0.5f
        )

        // =========================================================
        // 🔵 POLES
        // =========================================================
        for (pole in sceneManager.poles) {
            val poleDir = sceneManager.getPoleDirection(pole)

            val (verts, faces) = cylinder.generateCylinderMeshWorld(
                pole.base.position,
                pole.top.position,
                radius = 0.05f,  // Specify radius
                segments = 24,
                cap = true
            )

            if (verts.isNotEmpty() && faces.isNotEmpty()) {
                exporter.addMesh(
                    verts.map { it.position },
                    faces,
                    verts.map { it.normal },
                    material_pole
                )
            }

            // =====================================================
            // 🟫 CROSS ARMS
            // =====================================================
            pole.crossArms.forEach { arm ->
                val (aVerts, aFaces) = cylinder.generateCylinderMeshWorld(
                    arm.localStart,
                    arm.localEnd,
                    radius = 0.02f,
                    segments = 12,
                    cap = true
                )

                if (aVerts.isNotEmpty() && aFaces.isNotEmpty()) {
                    exporter.addMesh(
                        aVerts.map { it.position },
                        aFaces,
                        aVerts.map { it.normal },
                        material_arm
                    )
                }

                // =====================================================
                // 🔩 GENERATE 3 INSULATORS AND BOLTS
                // =====================================================
                val insulatorBasePoints = sceneManager.getAttachmentPointsOnArm(arm)
                val insulatorTipPoints = sceneManager.getInsulatorTipPoints(arm, poleDir)

                // Generate insulator and bolt for each phase
                insulatorBasePoints.forEachIndexed { index, basePoint ->
                    val tipPoint = insulatorTipPoints[index]
                    val insulatorDir = sceneManager.getInsulatorDirection(arm, poleDir, basePoint, index)

                    // Generate insulator mesh (from base to tip)
                    val (iVerts, iFaces) = cylinder.generateInsulatorMeshWithPoints(
                        basePoint,
                        tipPoint  // Pass both points for proper orientation
                    )

                    if (iVerts.isNotEmpty() && iFaces.isNotEmpty()) {
                        exporter.addMesh(
                            iVerts.map { it.position },
                            iFaces,
                            iVerts.map { it.normal },
                            material_insulator
                        )
                    }

                    // Generate bolt at the attachment point (where insulator meets cross arm)
                    val (bVerts, bFaces) = cylinder.generateBoltMeshAligned(
                        center = basePoint,
                        direction = insulatorDir,
                        radius = 0.008f,
                        height = 0.012f
                    )

                    if (bVerts.isNotEmpty() && bFaces.isNotEmpty()) {
                        exporter.addMesh(
                            bVerts.map { it.position },
                            bFaces,
                            bVerts.map { it.normal },
                            material_bolt
                        )
                    }
                }
            }
        }

        // =========================================================
        // 🔌 WIRES
        // =========================================================
        for (wire in sceneManager.wires) {
            val (wVerts, wFaces) = cylinder.generateWireSplineMesh(
                wire.points,
                radius = 0.008f,
                segments = 8,
                resolution = 8
            )

            if (wVerts.isNotEmpty() && wFaces.isNotEmpty()) {
                exporter.addMesh(
                    wVerts.map { it.position },
                    wFaces,
                    wVerts.map { it.normal },
                    material_wire
                )
            }
        }

        return exporter.buildGLB()
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        val session = session ?: return
        if (camera.trackingState != TrackingState.TRACKING) return


        val tapHelper = activity.view.tapHelper
        if (tapHelper.isDragging()) return

//        val tap = activity.view.tapHelper.pollTap()
        val tap = tapHelper.pollTap() ?: return

        val selected = findSelectedAnchor(frame, tap)
        if(selected != null){
            selectedAnchor = selected
            currentMode = InteractionMode.SELECTED
            return
        }
            tapHandler.onTap(session,frame, camera, tap)

    }

    fun findSelectedAnchor(frame: Frame, tap: MotionEvent): WrappedAnchor? {

        val thresholdPx = 100f

        val screenWidth = activity.view.surfaceView.width
        val screenHeight = activity.view.surfaceView.height

        for (wa in wrappedAnchors) {

            val pose = wa.anchor.pose
            val world = floatArrayOf(pose.tx(), pose.ty(), pose.tz())

            val screen = worldToScreen(
                frame,
                world,
                screenWidth,
                screenHeight
            )

            val dx = screen[0] - tap.x
            val dy = screen[1] - tap.y

            if (dx * dx + dy * dy < thresholdPx * thresholdPx) {
                return wa
            }
        }
        return null
    }

    fun worldToScreen(
        frame: Frame,
        world: FloatArray,
        screenWidth: Int,
        screenHeight: Int
    ): FloatArray {

        val view = FloatArray(16)
        val proj = FloatArray(16)
        val vp = FloatArray(16)
        val temp = FloatArray(4)

        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(proj, 0, 0.1f, 100f)

        // VP = Projection * View
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val input = floatArrayOf(world[0], world[1], world[2], 1f)

        Matrix.multiplyMV(temp, 0, vp, 0, input, 0)

        if (temp[3] == 0f) return floatArrayOf(-1f, -1f)

        val ndcX = temp[0] / temp[3]
        val ndcY = temp[1] / temp[3]

        val x = ((ndcX + 1f) / 2f) * screenWidth
        val y = ((1f - ndcY) / 2f) * screenHeight

        return floatArrayOf(x, y)
    }


    private fun showError(errorMessage: String) =
        activity.view.snackbarHelper.showError(activity, errorMessage)
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
public data class WrappedAnchor(
    var anchor: Anchor,
    val trackable: Trackable?,
    var isSelected: Boolean = false
)

data class Vec3(
    var x: Float,
    var y: Float,
    var z: Float
)

data class AnchorPoint(
    var anchor: Anchor,
    var position: Vec3
)

data class Pole(
    val base: AnchorPoint,
    val top: AnchorPoint,
    val crossArms: MutableList<CrossArm> = mutableListOf()
)

data class CrossArm(
    val localStart: Vec3,
    val localEnd: Vec3,
    val id: String = java.util.UUID.randomUUID().toString(),
    val config: PhaseConfig = PhaseConfig.HORIZONTAL
)

data class Wire(
    val startInsulator: Vec3,
    val endInsulator: Vec3,
    val points: List<Vec3>,
    var tension: Float = 1.0f
)

enum class InteractionMode {
    NONE,
    ADD,
    SELECTED,
    DRAGGING
}

enum class PhaseConfig {
    HORIZONTAL,
    VERTICAL,
    DELTA
}

data class CaptureFBO(
    val framebuffer: Int,
    val texture: Int,
    val depthBuffer: Int,
    val width: Int,
    val height: Int
)