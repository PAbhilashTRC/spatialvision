/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wsp.plugins.spatialvision.helloar

import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.provider.MediaStore
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Config
import com.wsp.plugins.spatialvision.R
import com.wsp.plugins.spatialvision.common.helpers.SnackbarHelper
import com.wsp.plugins.spatialvision.common.helpers.TapHelper
import android.widget.SeekBar
import android.widget.TextView
import android.widget.LinearLayout

/** Contains UI elements for Hello AR. */
class HelloArView(val activity: HelloArActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.spatial_vision, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)

  val slider = root.findViewById<SeekBar>(R.id.radius_slider)
  val reticleOverlay = root.findViewById<ReticleOverlayView>(R.id.reticle_overlay)

//  val slider_value = root.findViewById<TextView>(R.id.slider_value)

//  val pipeRadius = root.findViewById<TextView>( R.id.radius)
  val captureBtn = root.findViewById<ImageButton>(R.id.btn_capture)

  // New simplified UI elements
  val btnAddPoint = root.findViewById<ImageButton>(R.id.btn_add_point)
  val btnAddText = root.findViewById<TextView>(R.id.btn_add_text)
  val btnReset = root.findViewById<ImageButton>(R.id.btn_reset)
  val btnToggleMode = root.findViewById<ImageButton>(R.id.btn_toggle_mode)
  val btnCapture = root.findViewById<ImageButton>(R.id.btn_capture)
  val btnSettings = root.findViewById<ImageButton>(R.id.settings_button)
  val closeButton = root.findViewById<android.widget.Button>(R.id.close_button)

  // Measurement info panel
  val measurementInfoPanel = root.findViewById<LinearLayout>(R.id.measurement_info_panel)
  val pointsCountText = root.findViewById<TextView>(R.id.points_count_text)
  val measurementsCountText = root.findViewById<TextView>(R.id.measurements_count_text)
  val totalDistanceText = root.findViewById<TextView>(R.id.total_distance_text)

  // Last measurement panel
  val lastMeasurementPanel = root.findViewById<LinearLayout>(R.id.last_measurement_panel)
  val lastMeasurementText = root.findViewById<TextView>(R.id.last_measurement_text)

  val modeText = root.findViewById<TextView>(R.id.mode_text)
  var showCardLabel = false
  var isAutoMode = true

  private var isStartPointPlaced = false
  private var isEndPointPlaced = false

  private var pointsCount = 0
  private var measurementsCount = 0
  private var totalDistance = 0f

  val settingsButton =
    root.findViewById<ImageButton>(R.id.settings_button).apply {
      setOnClickListener { v ->
        PopupMenu(activity, v).apply {
          inflate(R.menu.settings_menu)

          menu.findItem(R.id.card)?.isChecked = showCardLabel

          setOnMenuItemClickListener { item ->
            when (item.itemId) {
              R.id.depth_settings -> launchDepthSettingsMenuDialog()
              R.id.instant_placement_settings -> launchInstantPlacementSettingsMenuDialog()
              R.id.card -> {
                showCardLabel = !showCardLabel
                item.isChecked = showCardLabel
                true
              }
              else -> null
            } != null
          }
//          inflate(R.menu.settings_menu)
          show()
        }
      }
    }

  val session
    get() = activity.arCoreSessionHelper.session

  val snackbarHelper = SnackbarHelper()
  val tapHelper = TapHelper(activity).also { surfaceView.setOnTouchListener(it) }
//  surfaceView.setOnTouchListener(dragHelper)

  // Callback interface for anchor operations
  // Callback interface
  interface MeasurementCallbacks {
    fun onAddPoint()
    fun onReset()
    fun onToggleMode(isAutoMode: Boolean)
    fun onCapture()
    fun onSettings()
    fun onClose()
  }

  var callbacks: MeasurementCallbacks? = null

  init {
    setupButtonListeners()
    updateModeUI()
  }

  private fun setupButtonListeners() {
    btnAddPoint.setOnClickListener {
      callbacks?.onAddPoint()
    }

    btnReset.setOnClickListener {
      callbacks?.onReset()
    }

    btnToggleMode.setOnClickListener {
      val isAutoMode = modeText.text != "Auto"
      callbacks?.onToggleMode(!isAutoMode)
      updateModeUI()
    }

    btnCapture.setOnClickListener {
      callbacks?.onCapture()
    }

    btnSettings.setOnClickListener {
      callbacks?.onSettings()
    }

    closeButton.setOnClickListener {
      callbacks?.onClose()
    }
  }

  private fun updateModeUI() {
    val isAutoMode = modeText.text == "Auto" || modeText.text == "Auto" // Toggle logic
    if (isAutoMode) {
      btnToggleMode.setImageResource(R.drawable.ic_auto_mode)
      modeText.text = "Auto"
      btnToggleMode.setColorFilter(Color.parseColor("#4CAF50"))
    } else {
      btnToggleMode.setImageResource(R.drawable.ic_manual_mode)
      modeText.text = "Manual"
      btnToggleMode.setColorFilter(Color.parseColor("#FF9800"))
    }
  }

  fun updateAddButtonState(pointsCount: Int) {
    this.pointsCount = pointsCount
    val isOdd = pointsCount % 2 == 1

    if (isOdd) {
      // Waiting for second point to complete measurement
      btnAddText.text = "Add End"
      btnAddPoint.setBackgroundResource(R.drawable.add_button_background_end)
      btnAddPoint.setColorFilter(Color.parseColor("#FF9800"))
    } else {
      // Ready for new measurement
      btnAddText.text = "Add Start"
      btnAddPoint.setBackgroundResource(R.drawable.add_button_background)
      btnAddPoint.setColorFilter(Color.parseColor("#4CAF50"))
    }

    updatePointsDisplay()
  }

  fun updateMeasurementStats(measurementsCount: Int, totalDistance: Float) {
    this.measurementsCount = measurementsCount
    this.totalDistance = totalDistance
    updateMeasurementsDisplay()
  }

  fun updateLastMeasurement(distance: Float, measurementNumber: Int) {
    lastMeasurementPanel.visibility = View.VISIBLE
    lastMeasurementText.text = String.format("#%d: %.2f m", measurementNumber, distance)

    // Auto-hide after 3 seconds
    lastMeasurementPanel.postDelayed({
      if (lastMeasurementPanel.visibility == View.VISIBLE) {
        lastMeasurementPanel.visibility = View.GONE
      }
    }, 3000)
  }

  private fun updatePointsDisplay() {
    pointsCountText.text = "Points: $pointsCount / 50"

    val measurementsFromPoints = pointsCount / 2
    if (measurementsFromPoints != measurementsCount) {
      measurementsCountText.text = "Measurements: $measurementsFromPoints"
    } else {
      measurementsCountText.text = "Measurements: $measurementsCount"
    }

    totalDistanceText.text = String.format("Total: %.2f m", totalDistance)

    // Show/hide info panel based on points
    measurementInfoPanel.visibility = if (pointsCount > 0) View.VISIBLE else View.GONE
  }

  private fun updateMeasurementsDisplay() {
    measurementsCountText.text = "Measurements: $measurementsCount"
    totalDistanceText.text = String.format("Total: %.2f m", totalDistance)
    measurementInfoPanel.visibility = if (measurementsCount > 0 || pointsCount > 0) View.VISIBLE else View.GONE
  }

  fun resetUI() {
    pointsCount = 0
    measurementsCount = 0
    totalDistance = 0f
    updateAddButtonState(0)
    updateMeasurementStats(0, 0f)
    measurementInfoPanel.visibility = View.GONE
    lastMeasurementPanel.visibility = View.GONE

    snackbarHelper.showMessage(activity, "All measurements cleared")
  }

  fun showMessage(message: String, isError: Boolean = false) {
    if (isError) {
      snackbarHelper.showError(activity, message)
    } else {
      snackbarHelper.showMessage(activity, message)
    }
  }

  fun updateModeText(isAutoMode: Boolean) {
    modeText.text = if (isAutoMode) "Auto" else "Manual"
    updateModeUI()
  }

  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }

  fun saveBitmap(context: Context, bitmap: Bitmap) {
    val filename = "AR_${System.currentTimeMillis()}.png"

    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, filename)
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ARCaptures")
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

    uri?.let {
      resolver.openOutputStream(it)?.use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
      }
    }
  }

  /**
   * Shows a pop-up dialog on the first tap in HelloARRenderer, determining whether the user wants
   * to enable depth-based occlusion. The result of this dialog can be retrieved with
   * DepthSettings.useDepthForOcclusion().
   */
  fun showOcclusionDialogIfNeeded() {
    val session = session ?: return
    val isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
    if (!activity.depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
      return // Don't need to show dialog.
    }

    // Asks the user whether they want to use depth-based occlusion.
    AlertDialog.Builder(activity)
      .setTitle(R.string.options_title_with_depth)
      .setMessage(R.string.depth_use_explanation)
      .setPositiveButton(R.string.button_text_enable_depth) { _, _ ->
        activity.depthSettings.setUseDepthForOcclusion(true)
      }
      .setNegativeButton(R.string.button_text_disable_depth) { _, _ ->
        activity.depthSettings.setUseDepthForOcclusion(false)
      }
      .show()
  }

  private fun launchInstantPlacementSettingsMenuDialog() {
    val resources = activity.resources
    val strings = resources.getStringArray(R.array.instant_placement_options_array)
    val checked = booleanArrayOf(activity.instantPlacementSettings.isInstantPlacementEnabled)
    AlertDialog.Builder(activity)
      .setTitle(R.string.options_title_instant_placement)
      .setMultiChoiceItems(strings, checked) { _, which, isChecked -> checked[which] = isChecked }
      .setPositiveButton(R.string.done) { _, _ ->
        val session = session ?: return@setPositiveButton
        activity.instantPlacementSettings.isInstantPlacementEnabled = checked[0]
        activity.configureSession(session)
      }
      .show()
  }

  /** Shows checkboxes to the user to facilitate toggling of depth-based effects. */
  private fun launchDepthSettingsMenuDialog() {
    val session = session ?: return

    // Shows the dialog to the user.
    val resources: Resources = activity.resources
    val checkboxes =
      booleanArrayOf(
        activity.depthSettings.useDepthForOcclusion(),
        activity.depthSettings.depthColorVisualizationEnabled()
      )
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      // With depth support, the user can select visualization options.
      val stringArray = resources.getStringArray(R.array.depth_options_array)
      AlertDialog.Builder(activity)
        .setTitle(R.string.options_title_with_depth)
        .setMultiChoiceItems(stringArray, checkboxes) { _, which, isChecked ->
          checkboxes[which] = isChecked
        }
        .setPositiveButton(R.string.done) { _, _ ->
          activity.depthSettings.setUseDepthForOcclusion(checkboxes[0])
          activity.depthSettings.setDepthColorVisualizationEnabled(checkboxes[1])
        }
        .show()
    } else {
      // Without depth support, no settings are available.
      AlertDialog.Builder(activity)
        .setTitle(R.string.options_title_without_depth)
        .setPositiveButton(R.string.done) { _, _ -> /* No settings to apply. */ }
        .show()
    }
  }
}