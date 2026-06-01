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
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Environment
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
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/** Contains UI elements for Hello AR. */
class PoleArView(val activity: PoleArActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.spatial_vision, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)

  val slider = root.findViewById<SeekBar>(R.id.radius_slider)
//  val slider_value = root.findViewById<TextView>(R.id.slider_value)

//  val pipeRadius = root.findViewById<TextView>( R.id.radius)

  var onExportRequested: (() -> Unit)? = null

//  var onPhaseConfigChange: PhaseConfig = PhaseConfig.VERTICAL

  var onPhaseConfigChange: ((PhaseConfig) -> Unit)? = null
  // Store reference to the current popup menu
  private var currentPopupMenu: PopupMenu? = null
  var showCardLabel = false

  val captureBtn = root.findViewById<ImageButton>(R.id.btn_capture)

//  val onCapture = captureBtn.apply {
//    setOnClickListener {
//      surfaceView.queueEvent {
//        renderer.captureFrame = true
//      }
//    }
//  }
  val settingsButton =
    root.findViewById<ImageButton>(R.id.settings_button).apply {
      setOnClickListener { v ->
        PopupMenu(activity, v).apply {
          currentPopupMenu = this
          inflate(R.menu.settings_menu)

          val current = activity.sceneManager.currentConfig

          menu.findItem(R.id.config_horizontal)?.isChecked =
            current == PhaseConfig.HORIZONTAL

          menu.findItem(R.id.config_vertical)?.isChecked =
            current == PhaseConfig.VERTICAL

          menu.findItem(R.id.config_delta)?.isChecked =
            current == PhaseConfig.DELTA

          menu.findItem(R.id.card)?.isChecked = showCardLabel

          setOnMenuItemClickListener { item ->
            when (item.itemId) {
              R.id.depth_settings -> launchDepthSettingsMenuDialog()
              R.id.instant_placement_settings -> launchInstantPlacementSettingsMenuDialog()
              R.id.config_horizontal -> {
                onPhaseConfigChange?.invoke(PhaseConfig.HORIZONTAL)
                true
              }
              R.id.config_vertical -> {
                onPhaseConfigChange?.invoke(PhaseConfig.VERTICAL)
                true
              }

              R.id.config_delta -> {
                onPhaseConfigChange?.invoke(PhaseConfig.DELTA)
                true
              }
              R.id.card -> {
                showCardLabel = !showCardLabel
                item.isChecked = showCardLabel
                true
              }
              R.id.download -> {
                onExportRequested?.invoke()
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

  fun saveObj(context: Context, content: String): File {
    val file = File(context.getExternalFilesDir(null), "scene.obj")
    file.writeText(content)
    return file
  }

  // Add a method to update menu item states
  fun updateMenuCheckedStates(config: PhaseConfig) {
    currentPopupMenu?.let { menu ->
      menu.menu.findItem(R.id.config_horizontal)?.isChecked = config == PhaseConfig.HORIZONTAL
      menu.menu.findItem(R.id.config_vertical)?.isChecked = config == PhaseConfig.VERTICAL
      menu.menu.findItem(R.id.config_delta)?.isChecked = config == PhaseConfig.DELTA
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun saveObjToDownloadsModern(context: Context, content: ByteArray): Uri? {

    val resolver = context.contentResolver
    // Create timestamp: yyyyMMdd_HHmmss
    val timestamp = java.text.SimpleDateFormat(
      "yyyyMMdd_HHmmss",
      java.util.Locale.getDefault()
    ).format(java.util.Date())

    val fileName = "scene_$timestamp.glb"
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, fileName)
      put(MediaStore.Downloads.MIME_TYPE, "model/gltf-binary")
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }

    val uri = resolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      values
    ) ?: return null

    resolver.openOutputStream(uri)?.use { output ->
      output.write(content)
    }

    return uri
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

  fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(intent, "Download OBJ"))
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