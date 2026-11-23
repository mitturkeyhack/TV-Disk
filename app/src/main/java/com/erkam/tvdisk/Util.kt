package com.erkam.tvdisk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import java.util.Locale

fun Context.registerReceiverCompat(r: BroadcastReceiver, f: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
    else registerReceiver(r, f)
}

fun Activity.color(id: Int): Int =
    if (Build.VERSION.SDK_INT >= 23) getColor(id) else @Suppress("DEPRECATION") resources.getColor(id)

fun gb(bytes: Long): String = String.format(Locale("tr"), "%.1f GB", bytes / 1e9)
