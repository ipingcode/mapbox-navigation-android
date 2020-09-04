package com.mapbox.navigation.core.telemetry

import java.util.ArrayDeque

internal data class EventBuffers<T>(
    val preEventBuffer: ArrayDeque<T>,
    val postEventBuffer: ArrayDeque<T>,
    val onBufferFull: (ArrayDeque<T>, ArrayDeque<T>) -> Unit
)
