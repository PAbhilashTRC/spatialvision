package com.wsp.plugins.spatialvision.helloar

//import com.wsp.plugins.spatialvision.common.samplerender.arcore.PlaneRenderer
//import com.wsp.plugins.spatialvision.GeoSpatial
import android.annotation.SuppressLint
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
    val modelMatrix = FloatArray(16)
    val baseModelMatrix = FloatArray(16)
    val topModelMatrix = FloatArray(16)
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val modelViewMatrix = FloatArray(16) // view x model

    val labelViewProjectionMatrix  = FloatArray(16) // view x projection

    private lateinit var labelRenderer: LabelRender

    val isCard = activity.view.showCardLabel
    var depthConfidence: Int? = null;

//    private var cylinder: Cylinder? = null
    private lateinit var cylinder: Cylinder
    private lateinit var poleWire: PoleWire

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

    enum class Mode {
        AUTO, POLE, WIRE
    }
    private var exporter: ObjExporter = ObjExporter()

    private lateinit var sceneManager: SceneManager
    private lateinit var tapHandler: TapHandler

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

//            poleWire = PoleWire().also{
//                it.onSurfaceCreated(render = render)
//            }

            sceneManager = SceneManager(cylinder)
            tapHandler = TapHandler(sceneManager)

            activity.view.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

                @SuppressLint("SetTextI18n")
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val minRadius = 0.01f   // 1 cm
                    val maxRadius = 0.5f    // 50 cm

                    val t = progress / 100f
                    val radiusMeters = minRadius + t * (maxRadius - minRadius) // 0.01 + ( progress/100) * (0.5-0.01)
                    val radiusCentimeters = minRadius + progress * (maxRadius - minRadius) // 0.01 + ( progress/100) * (0.5-0.01)
                    activity.view.pipeRadius.text = "Radius: $radiusCentimeters cm"
//                    activity.view.slider_value.text = "Slider Value : $progress"
//                val radius = progress / 1000f  // scale factor
                    cylinder?.setRadius(radiusMeters)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        virtualSceneFramebuffer.resize(width, height)
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
        handleDrag(frame, camera)

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
                    uColor = floatArrayOf(0.45f, 0.35f, 0.25f, 1.0f), asset = "Crass_arms")
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

                val attachmentPoints = sceneManager.getAttachmentPoints(arm)

                attachmentPoints.forEach { p ->
                    // 🔥 direction should be from insulator → opposite pole
                    val wireDir = MathUtils.normalize(
                        floatArrayOf(
                            arm.localEnd.x - arm.localStart.x,
                            arm.localEnd.y - arm.localStart.y,
                            arm.localEnd.z - arm.localStart.z
                        )
                    )

                    val gravityBias = floatArrayOf(0f, -0.2f, 0f)

                    val tiltDir = MathUtils.normalize(
                        floatArrayOf(
                            wireDir[0] + gravityBias[0],
                            wireDir[1] + gravityBias[1],
                            wireDir[2] + gravityBias[2]
                        )
                    )

                    cylinder.drawInsulator(
                        render,
                        p,          // ✅ USE ACTUAL POINT
                        tiltDir,
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
            for (i in 0 until wire.points.size - 1) {
                val p1 = wire.points[i]
                val p2 = wire.points[i + 1]
                cylinder.setRadius(0.005f)
                cylinder.draw(
                    render,
                    p1,
                    p2,
                    viewMatrix,
                    projectionMatrix,
                    floatArrayOf(0.50f, 0.52f, 0.54f, 1.0f),
                    asset = "wires"
                )
            }

        }

        // --- Compose final scene ---
        backgroundRenderer.drawVirtualScene(
            render,
            virtualSceneFramebuffer,
            Z_NEAR,
            Z_FAR
        )
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

//    fun exportSceneGLB(): ByteArray {
//        val material_pole = 0
//        val material_arm = 1
//        val material_insulator = 2
//        val material_wire = 3
//        val exporter = GlbExporter()
//
//        // =========================================================
//        // 🔵 POLES
//        // =========================================================
//        for (pole in sceneManager.poles) {
//
//            val (verts, faces) = cylinder.generateCylinderMeshWorld(
//                pole.base.position,
//                pole.top.position
//            )
//
//            exporter.addMesh(verts.map {it.position}, faces, verts.map {it.normal}, material_pole)
//
//            // =====================================================
//            // 🟫 CROSS ARMS
//            // =====================================================
//            pole.crossArms.forEach { arm ->
//
//                val (aVerts, aFaces) = cylinder.generateCylinderMeshWorld(
//                    arm.localStart,
//                    arm.localEnd
//                )
//
//                exporter.addMesh(aVerts.map {it.position},
//                    aFaces,
//                    aVerts.map {it.normal},
//                    material_arm)
//
//                // =====================================================
//                // 🔩 INSULATOR
//                // =====================================================
//                val mid = Vec3(
//                    (arm.localStart.x + arm.localEnd.x) * 0.5f,
//                    (arm.localStart.y + arm.localEnd.y) * 0.5f,
//                    (arm.localStart.z + arm.localEnd.z) * 0.5f
//                )
//
//                val dir = MathUtils.normalize(
//                    floatArrayOf(
//                        arm.localEnd.x - arm.localStart.x,
//                        arm.localEnd.y - arm.localStart.y,
//                        arm.localEnd.z - arm.localStart.z
//                    )
//                )
//
//                val (iVerts, iFaces) = cylinder.generateInsulatorMesh(mid, dir)
//
//                exporter.addMesh(iVerts.map {it.position},
//                    iFaces,
//                    iVerts.map {it.normal},
//                    material_insulator)
//            }
//        }
//
//        // =========================================================
//        // 🔌 WIRES (FIXED CONCEPT)
//        // =========================================================
//        for (wire in sceneManager.wires) {
//
//            val (wVerts, wFaces) = cylinder.generateWireSplineMesh(wire.points)
//
//            exporter.addMesh(wVerts.map {it.position},
//                wFaces, wVerts.map { it.normal},
//                material_wire)
//        }
//
//        return exporter.buildGLB()
//    }

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

        val material_wire = exporter.addMaterial(
            floatArrayOf(0.3f, 0.3f, 0.3f, 1.0f),   // Dark gray
            0.7f,
            0.5f
        )

        // =========================================================
        // 🔵 POLES
        // =========================================================
        for (pole in sceneManager.poles) {
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
                // 🔩 INSULATOR
                // =====================================================
                val mid = Vec3(
                    (arm.localStart.x + arm.localEnd.x) * 0.5f,
                    (arm.localStart.y + arm.localEnd.y) * 0.5f,
                    (arm.localStart.z + arm.localEnd.z) * 0.5f
                )

                val dir = MathUtils.normalize(
                    floatArrayOf(
                        arm.localEnd.x - arm.localStart.x,
                        arm.localEnd.y - arm.localStart.y,
                        arm.localEnd.z - arm.localStart.z
                    )
                )

                val (iVerts, iFaces) = cylinder.generateInsulatorMesh(mid, dir)

                if (iVerts.isNotEmpty() && iFaces.isNotEmpty()) {
                    exporter.addMesh(
                        iVerts.map { it.position },
                        iFaces,
                        iVerts.map { it.normal },
                        material_insulator
                    )
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

    private fun handleDrag(frame: Frame, camera: Camera) {
        val session = session ?: return

        if (camera.trackingState != TrackingState.TRACKING) return


        val tapHelper = activity.view.tapHelper

        // 👉 Not dragging → reset state safely
        if (!tapHelper.isDragging()) {
            if (currentMode == InteractionMode.DRAGGING ||
                currentMode == InteractionMode.SELECTED) {

                selectedAnchor?.isSelected = false
                selectedAnchor = null
                currentMode = InteractionMode.NONE
            }
            return
        }

        val event = tapHelper.pollDrag() ?: return

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val selected = findSelectedAnchor(frame, event)

                if (selected != null) {
                    selectedAnchor = selected
                    selected.isSelected = true
                    currentMode = InteractionMode.SELECTED
                }
            }

            MotionEvent.ACTION_MOVE -> {

                if (currentMode == InteractionMode.SELECTED && selectedAnchor != null) {
                    currentMode = InteractionMode.DRAGGING
                }

                if (currentMode == InteractionMode.DRAGGING && selectedAnchor != null) {

                    // 🔥 THROTTLE (critical for stability)
                    if (System.currentTimeMillis() - lastUpdateTime < 50) return

                    val newAnchor =
                        tapHandler.getStableDepthAnchor(session, frame, camera, event)
                            ?: frame.hitTest(event.x, event.y)
                                .firstOrNull {
                                    it.trackable is DepthPoint ||
                                            it.trackable is Plane
                                }?.createAnchor()

                    if (newAnchor != null) {
                        // 🔥 APPLY DRAG
                        selectedAnchor?.anchor?.detach()
                        selectedAnchor?.anchor = newAnchor

                        lastUpdateTime = System.currentTimeMillis()
                    }
                }
            }
        }
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
) {
fun mid(): Vec3 {
    return Vec3(
        (localStart.x + localEnd.x) * 0.5f,
        (localStart.y + localEnd.y) * 0.5f,
        (localStart.z + localEnd.z) * 0.5f
    )
}
}
//
//data class Wire(
//    val startArm: CrossArm,
//    val endArm: CrossArm,
//    var points: List<Vec3> = emptyList(),
//    var tension: Float = 1.0f
//)

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

data class Insulator(
    val position: Vec3,
    val wireDir: Vec3
)