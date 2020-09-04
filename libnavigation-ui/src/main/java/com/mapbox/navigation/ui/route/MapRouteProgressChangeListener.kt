package com.mapbox.navigation.ui.route

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import timber.log.Timber

/**
 * Upon receiving route progress events draws and/or updates the line on the map representing the
 * route as well as arrow(s) representing the next maneuver.
 *
 * @param routeLine the route to represent on the map
 * @param routeArrow the arrow representing the next maneuver
 */
internal class MapRouteProgressChangeListener(
    private val routeLine: MapRouteLine,
    private val routeArrow: MapRouteArrow
) : RouteProgressObserver {

    private var restoreRouteArrowVisibilityFun: DeferredRouteUpdateFun? = null
    private var shouldReInitializePrimaryRoute = false

    override fun onRouteProgressChanged(routeProgress: RouteProgress) {
        onProgressChange(routeProgress)
    }

    private fun onProgressChange(routeProgress: RouteProgress) {
        val directionsRoute = routeLine.getPrimaryRoute()
        updateRoute(directionsRoute, routeProgress)
    }

    private fun updateRoute(directionsRoute: DirectionsRoute?, routeProgress: RouteProgress) {
        val currentRoute = routeProgress.route
        val hasGeometry = currentRoute.geometry()?.isNotEmpty() ?: false
        if (hasGeometry && currentRoute != directionsRoute) {
            routeLine.reinitializeWithRoutes(listOf(currentRoute))
            shouldReInitializePrimaryRoute = true

            restoreRouteArrowVisibilityFun = getRestoreArrowVisibilityFun(routeArrow.routeArrowIsVisible())
            routeArrow.updateVisibilityTo(false)
        } else {
            restoreRouteArrowVisibilityFun?.invoke()
            restoreRouteArrowVisibilityFun = null

            if (routeArrow.routeArrowIsVisible()) {
                routeArrow.addUpcomingManeuverArrow(routeProgress)
            }

            if (hasGeometry) {
                if (shouldReInitializePrimaryRoute) {
                    routeLine.reinitializePrimaryRoute()
                    shouldReInitializePrimaryRoute = false
                }
            }
        }

        val percentDistanceTraveled = getPercentDistanceTraveled(routeProgress)
        routeLine.updateDistanceTraveledCache(routeProgress.route, percentDistanceTraveled)

        Timber.e("*** state ${routeProgress.currentState}")
        when (routeProgress.currentState) {
            RouteProgressState.LOCATION_TRACKING -> routeLine.inhibitVanishingPointUpdate(false)
            RouteProgressState.ROUTE_COMPLETE -> routeLine.inhibitVanishingPointUpdate(false)
            else -> routeLine.inhibitVanishingPointUpdate(true)
        }
    }

    private fun getPercentDistanceTraveled(routeProgress: RouteProgress): Float {
        val totalDist =
            (routeProgress.distanceRemaining + routeProgress.distanceTraveled)
        return routeProgress.distanceTraveled / totalDist
    }

    private fun getRestoreArrowVisibilityFun(isVisible: Boolean): DeferredRouteUpdateFun = {
        routeArrow.updateVisibilityTo(isVisible)
    }
}
internal typealias DeferredRouteUpdateFun = () -> Unit
