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
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import java.io.File
import java.io.Serializable

/** Contains UI elements for Hello AR. */
class HelloArView(val activity: HelloArActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.spatial_vision, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)

//  val slider = root.findViewById<SeekBar>(R.id.radius_slider)
//  val reticleOverlay = root.findViewById<ReticleOverlayView>(R.id.reticle_overlay)


  val captureBtn = root.findViewById<ImageButton>(R.id.btn_capture)
  val doneBtn = root.findViewById<Button>(R.id.done_button)
  val btnAddPoint = root.findViewById<ImageButton>(R.id.btn_add_point)
  val undoBtn = root.findViewById<ImageButton>(R.id.btn_undo)
  val btnReset = root.findViewById<ImageButton>(R.id.btn_reset)
//  val measurementNames = mutableMapOf<Int, String>()
  val measurements = mutableListOf<Measurement>()
  val session
    get() = activity.arCoreSessionHelper.session

  val snackbarHelper = SnackbarHelper()
  val tapHelper = TapHelper(activity).also { surfaceView.setOnTouchListener(it) }
//  surfaceView.setOnTouchListener(dragHelper)

  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }

  interface MeasurementCallbacks {
//    fun onAddPoint()
    fun onReset()
    fun onUndo()
  }

  var callbacks: MeasurementCallbacks? = null

  init {
    setupButtonListeners()
  }

  private fun setupButtonListeners() {
    btnReset.setOnClickListener {
      callbacks?.onReset()
      measurements.clear()
    }
    undoBtn.setOnClickListener {
      callbacks?.onUndo()
    }

  }
  fun saveBitmap(context: Context, bitmap: Bitmap): Uri? {
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
    return uri
  }

  fun saveBitmapToFile(context: Context, bitmap: Bitmap): File? {
    val filename = "AR_${System.currentTimeMillis()}.png"

    // Choose directory
    val dir = File(context.getExternalFilesDir(null), "ARCaptures")
    if (!dir.exists()) dir.mkdirs()

    val file = File(dir, filename)

    return try {
      file.outputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
      }
      file
    } catch (e: Exception) {
      e.printStackTrace()
      null
    }
  }

  fun showMeasurementInputDialog(index: Int) {
    val editText = EditText(this.activity)
    editText.hint = "Enter measurement name"

    AlertDialog.Builder(this.activity)
      .setTitle("Measurement Name")
      .setMessage("Give a name for this measurement")
      .setView(editText)
      .setCancelable(false)
      .setPositiveButton("OK") { _, _ ->
        val input = editText.text.toString().trim()
        measurements[index].label =
          if (input.isNotEmpty()) input else "Measurement ${index + 1}"
      }
      .setNegativeButton("Skip") { _, _ ->
        measurements[index].label = "Measurement ${index + 1}"
      }
      .show()
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

  fun resetUI() {
    snackbarHelper.showMessage(activity, "All measurements cleared")
  }

}
data class Measurement(
    var label: String,
    var distance: Float,
    var unit: String = "m",
    var startPoint: String = "",
    var endPoint: String = ""
): Serializable