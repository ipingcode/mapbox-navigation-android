package com.mapbox.navigation.core.telemetry

import android.location.Location
import android.util.Log
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.telemetry.MapboxNavigationTelemetry.LOCATION_BUFFER_MAX_SIZE
import com.mapbox.navigation.core.telemetry.MapboxNavigationTelemetry.TAG
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.utils.internal.ThreadController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import java.util.Collections

internal class TelemetryLocationAndProgressDispatcher :
    RouteProgressObserver, LocationObserver, RoutesObserver, OffRouteObserver {

    private val channelOffRouteEvent = Channel<Boolean>(Channel.CONFLATED)
    private val channelRoute = Channel<DirectionsRoute>(Channel.CONFLATED)
    private val channelRouteProgress = Channel<RouteProgress>(Channel.CONFLATED)

    private val locationBuffer = SynchronizedItemBuffer<Location>()
    private val locationEventBuffer = SynchronizedItemBuffer<EventBuffers<Location>>()

    val lastLocation: Location? = locationBuffer.getItem(0)
    var routeProgress: RouteProgress? = null
        private set
    var firstLocationDeffered = CompletableDeferred<Location>()
        private set
    var originalRouteDeffered = CompletableDeferred<DirectionsRoute>()
        private set

    private var routeCompleted = false

    /**
     * This class provides thread-safe access to a mutable list of locations
     */
    // TODO replace with concurrent collection
    private class SynchronizedItemBuffer<T> {
        private val synchronizedCollection: MutableList<T> =
            Collections.synchronizedList(mutableListOf<T>())

        fun addFirst(item: T) {
            synchronized(synchronizedCollection) {
                synchronizedCollection.add(0, item)
            }
        }

        fun getItem(index: Int): T {
            synchronized(synchronizedCollection) {
                return synchronizedCollection[index]
            }
        }

        fun removeLast() {
            synchronized(synchronizedCollection) {
                if (synchronizedCollection.isNotEmpty()) {
                    val index = synchronizedCollection.size - 1
                    synchronizedCollection.removeAt(index)
                }
            }
        }

        fun getCopy(): List<T> {
            val result = mutableListOf<T>()
            synchronized(synchronizedCollection) {
                result.addAll(synchronizedCollection)
            }
            return result
        }

        fun clear() {
            synchronized(synchronizedCollection) {
                synchronizedCollection.clear()
            }
        }

        fun applyToEachAndRemove(predicate: (T) -> Boolean) {
            synchronized(synchronizedCollection) {
                val iterator = synchronizedCollection.iterator()
                while (iterator.hasNext()) {
                    val nextItem = iterator.next()
                    if (predicate(nextItem)) {
                        iterator.remove()
                    }
                }
            }
        }

        fun forEach(predicate: (T) -> Unit) {
            synchronized(synchronizedCollection) {
                val iterator = synchronizedCollection.iterator()
                while (iterator.hasNext()) {
                    predicate(iterator.next())
                }
            }
        }

        fun size() = synchronizedCollection.size
    }

    /**
     * Process the location event buffer twice. The first time, update each of it's elements
     * with a new location object. On the second pass, execute the stored lambda if the buffer
     * size is equal to or greater than a given value.
     */
    private fun processLocationEventBuffer(location: Location) {
        locationEventBuffer.forEach { it.postEventBuffer.addFirst(location) }
        locationEventBuffer.applyToEachAndRemove { item ->
            if (item.postEventBuffer.size >= LOCATION_BUFFER_MAX_SIZE) {
                item.onBufferFull(item.preEventBuffer, item.postEventBuffer)
                true
            } else {
                // Do nothing.
                false
            }
        }
    }

    fun flushLocationEventBuffer() {
        Log.d(TAG, "flushing buffers before ${locationBuffer.size()}")
        locationEventBuffer.forEach { it.onBufferFull(it.preEventBuffer, it.postEventBuffer) }
    }

    /**
     * This method accumulates locations. The number of locations is limited by [MapboxNavigationTelemetry.LOCATION_BUFFER_MAX_SIZE].
     * Once this limit is reached, an item is removed before another is added.
     */
    private fun accumulateLocation(location: Location) {
        locationBuffer.run {
            if (size() >= LOCATION_BUFFER_MAX_SIZE) {
                removeLast()
            }
            addFirst(location)
        }
    }

    fun addLocationEventDescriptor(eventBuffers: EventBuffers<Location>) {
        eventBuffers.preEventBuffer.clear()
        eventBuffers.postEventBuffer.clear()
        eventBuffers.preEventBuffer.addAll(locationBuffer.getCopy())
        locationEventBuffer.addFirst(eventBuffers)
    }

    suspend fun clearLocationEventBuffer() {
        withContext(ThreadController.IODispatcher) {
            flushLocationEventBuffer()
            locationEventBuffer.clear()
        }
    }

    fun copyLocationBuffer() = locationBuffer.getCopy()

    fun resetRouteProgressProcessor() {
        routeCompleted = false
    }

    override fun onRouteProgressChanged(routeProgress: RouteProgress) {
        Log.d(TAG, "route progress state = ${routeProgress.currentState}")
        this.routeProgress = routeProgress
        if (!routeCompleted) {
            channelRouteProgress.offer(routeProgress)
            if (routeProgress.currentState == RouteProgressState.ROUTE_COMPLETE) {
                routeCompleted = true
            }
        }
    }

    fun getDirectionsRouteChannel(): ReceiveChannel<DirectionsRoute> = channelRoute

    fun getRouteProgressChannel(): ReceiveChannel<RouteProgress> = channelRouteProgress

    fun getOffRouteEventChannel(): ReceiveChannel<Boolean> = channelOffRouteEvent

    fun clearOriginalRoute() {
        originalRouteDeffered = CompletableDeferred()
    }

    override fun onRawLocationChanged(rawLocation: Location) {
        // Do nothing
    }

    override fun onEnhancedLocationChanged(enhancedLocation: Location, keyPoints: List<Location>) {
        accumulateLocation(enhancedLocation)
        processLocationEventBuffer(enhancedLocation)
        firstLocationDeffered.complete(enhancedLocation)
    }

    override fun onRoutesChanged(routes: List<DirectionsRoute>) {
        Log.d(TAG, "onRoutesChanged received. Route list size = ${routes.size}")
        if (routes.isNotEmpty()) {
            channelRoute.offer(routes[0])
            originalRouteDeffered.complete(routes[0])
        }
    }

    override fun onOffRouteStateChanged(offRoute: Boolean) {
        Log.d(TAG, "onOffRouteStateChanged $offRoute")
        channelOffRouteEvent.offer(offRoute)
    }
}
