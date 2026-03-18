package com.wsp.plugins.spatialvision

import android.util.Log

class SpatialVision {

    fun echo(value: String?): String? {
        Log.i("Echo", value ?: "null")

        return value
    }
}
