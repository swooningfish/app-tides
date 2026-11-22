package com.xrmx.tides.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [compute] off the main thread whenever [keys] change. Returns the latest result (the
 * previous one while a new one is computed), or null before the first result. Computes
 * synchronously in inspection mode (previews, headless screenshots), where effects don't run.
 */
@Composable
fun <T : Any> rememberComputed(vararg keys: Any?, compute: () -> T): T? {
    if (LocalInspectionMode.current) return remember(*keys) { compute() }
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(*keys) {
        value = withContext(Dispatchers.Default) { compute() }
    }
    return value
}
