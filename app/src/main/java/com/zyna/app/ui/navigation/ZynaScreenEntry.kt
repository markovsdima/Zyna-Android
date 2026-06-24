package com.zyna.app.ui.navigation

import android.content.Context
import android.view.View

data class ZynaScreenEntry(
    val key: String,
    val rootGlassOwnerKey: String? = null,
    val retainViewOnRemove: Boolean = false,
    val createView: (Context) -> View,
    val updateView: (View) -> Unit,
    val onViewRemoved: (View) -> Unit = {}
)
