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
//  val slider_value = root.findViewById<TextView>(R.id.slider_value)

//  val pipeRadius = root.findViewById<TextView>( R.id.radius)
  val captureBtn = root.findViewById<ImageButton>(R.id.btn_capture)

  // New UI Elements
  val btnStartPoint = root.findViewById<ImageButton>(R.id.btn_start_point)
  val btnEndPoint = root.findViewById<ImageButton>(R.id.btn_end_point)
  val btnReset = root.findViewById<ImageButton>(R.id.btn_reset)
  val btnToggleMode = root.findViewById<ImageButton>(R.id.btn_toggle_mode)
  val distanceContainer = root.findViewById<LinearLayout>(R.id.distance_container)
  val distanceValue = root.findViewById<TextView>(R.id.distance_value)
  val startPointStatus = root.findViewById<TextView>(R.id.start_point_status)
  val endPointStatus = root.findViewById<TextView>(R.id.end_point_status)
  val modeText = root.findViewById<TextView>(R.id.mode_text)
  var showCardLabel = false
  var isAutoMode = true

  private var isStartPointPlaced = false
  private var isEndPointPlaced = false

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
  interface AnchorUICallbacks {
    fun onPlaceStartPoint()
    fun onPlaceEndPoint()
    fun onResetAnchors()
    fun onToggleMode(isAutoMode: Boolean)
  }

  var callbacks: AnchorUICallbacks? = null

  init {
    setupButtonListeners()
    updateButtonStates()
    updateModeUI()
  }

  private fun setupButtonListeners() {
    btnStartPoint.setOnClickListener {
      if (!isStartPointPlaced) {
        callbacks?.onPlaceStartPoint()
      } else {
        snackbarHelper.showMessage(activity, "Start point already placed!")
      }
    }

    btnEndPoint.setOnClickListener {
      if (!isEndPointPlaced && isStartPointPlaced) {
        callbacks?.onPlaceEndPoint()
      } else if (!isStartPointPlaced) {
        snackbarHelper.showMessage(activity, "Please place start point first!")
      } else {
        snackbarHelper.showMessage(activity, "End point already placed!")
      }
    }

    btnReset.setOnClickListener {
      callbacks?.onResetAnchors()
    }

    btnToggleMode.setOnClickListener {
      isAutoMode = !isAutoMode
      updateModeUI()
      callbacks?.onToggleMode(isAutoMode)

      val modeMessage = if (isAutoMode) "Auto mode enabled" else "Manual mode enabled"
      snackbarHelper.showMessage(activity, modeMessage)
    }
  }

  private fun updateModeUI() {
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

  fun updateAnchorStatus(startPlaced: Boolean, endPlaced: Boolean) {
    isStartPointPlaced = startPlaced
    isEndPointPlaced = endPlaced
    updateButtonStates()
  }

  private fun updateButtonStates() {
    // Update start point button
    btnStartPoint.isEnabled = !isStartPointPlaced
    btnStartPoint.alpha = if (isStartPointPlaced) 0.5f else 1.0f
    startPointStatus.text = if (isStartPointPlaced) "Start ✓" else "Start"
    startPointStatus.setTextColor(if (isStartPointPlaced) Color.parseColor("#4CAF50") else Color.WHITE)

    // Update end point button
    btnEndPoint.isEnabled = isStartPointPlaced && !isEndPointPlaced
    btnEndPoint.alpha = if (!isStartPointPlaced || isEndPointPlaced) 0.5f else 1.0f
    endPointStatus.text = if (isEndPointPlaced) "End ✓" else "End"
    endPointStatus.setTextColor(if (isEndPointPlaced) Color.parseColor("#F44336") else Color.WHITE)

    // Show/hide distance container
    distanceContainer.visibility = if (isStartPointPlaced && isEndPointPlaced) View.VISIBLE else View.GONE
  }

  fun updateDistance(distanceMeters: Float) {
    distanceValue.text = String.format("%.3f m", distanceMeters)

    // Color code based on distance
    val color = when {
      distanceMeters < 1.0f -> Color.parseColor("#FF9800") // Orange for < 1m
      distanceMeters < 5.0f -> Color.parseColor("#4CAF50") // Green for 1-5m
      else -> Color.parseColor("#F44336") // Red for > 5m
    }
    distanceValue.setTextColor(color)
  }

  fun resetUI() {
    isStartPointPlaced = false
    isEndPointPlaced = false
    updateButtonStates()
    distanceContainer.visibility = View.GONE
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