/*
 * Copyright 2022 Google LLC
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
package com.wsp.plugins.spatialvision.hellogeospatial.helpers

import android.opengl.GLSurfaceView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.wsp.plugins.spatialvision.GeoSpatial
import com.wsp.plugins.spatialvision.examples.java.common.helpers.SnackbarHelper


class HelloGeoView(val activity: GeoSpatial) : DefaultLifecycleObserver {
val surfaceView = activity.originalView.findViewById<GLSurfaceView>(activity.resources.getIdentifier("surfaceview", "id", activity.requireActivity().packageName))

    val session
        get() = activity.arCoreSessionHelper.session

    val snackbarHelper = SnackbarHelper()

    var mapView: MapView? = null
    val mapTouchWrapper = activity.originalView.findViewById<MapTouchWrapper>(activity.resources.getIdentifier("map_wrapper", "id", activity.requireActivity().packageName)).apply {
    setup { screenLocation ->
            val latLng: LatLng =
                mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
            activity.renderer.onMapClick(latLng)
        }
    }
    val mapFragment =
        (activity.childFragmentManager.findFragmentById(activity.resources.getIdentifier("map", "id", activity.requireActivity().packageName)) as SupportMapFragment).also {
            it.getMapAsync { googleMap -> mapView = MapView(activity, googleMap) }
        }

    val statusText = activity.originalView.findViewById<TextView>(activity.resources.getIdentifier("statusText", "id", activity.requireActivity().packageName))
    fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
        activity.requireActivity().runOnUiThread {
            val poseText = if (cameraGeospatialPose == null) "" else
                activity.getString(activity.resources.getIdentifier("geospatial_pose", "string", activity.requireActivity().packageName),
                    cameraGeospatialPose.latitude,
                    cameraGeospatialPose.longitude,
                    cameraGeospatialPose.horizontalAccuracy,
                    cameraGeospatialPose.altitude,
                    cameraGeospatialPose.verticalAccuracy,
                    cameraGeospatialPose.heading,
                    cameraGeospatialPose.headingAccuracy)
            statusText.text = activity.resources.getString(activity.resources.getIdentifier("earth_state", "string", activity.requireActivity().packageName),
                earth.earthState.toString(),
                earth.trackingState.toString(),
                poseText)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        surfaceView.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        surfaceView.onPause()
    }
}
