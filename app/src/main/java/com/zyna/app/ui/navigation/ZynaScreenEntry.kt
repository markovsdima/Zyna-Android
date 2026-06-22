package com.zyna.app.ui.navigation

import android.content.Context
import android.view.View

data class ZynaScreenEntry(
    val key: String,
    val ownsRootGlassLayers: Boolean = false,
    val createView: (Context) -> View,
    val updateView: (View) -> Unit
)
