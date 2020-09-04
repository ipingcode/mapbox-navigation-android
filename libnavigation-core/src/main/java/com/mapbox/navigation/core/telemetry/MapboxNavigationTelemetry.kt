package com.mapbox.navigation.core.telemetry

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.telemetry.AppUserTurnstile
import com.mapbox.android.telemetry.TelemetryUtils
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.metrics.MetricEvent
import com.mapbox.navigation.base.metrics.MetricsReporter
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.base.trip.model.RouteProgressState.LOCATION_TRACKING
import com.mapbox.navigation.base.trip.model.RouteProgressState.ROUTE_COMPLETE
import com.mapbox.navigation.core.BuildConfig
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.NavigationSession.State
import com.mapbox.navigation.core.NavigationSession.State.ACTIVE_GUIDANCE
import com.mapbox.navigation.core.NavigationSession.State.FREE_DRIVE
import com.mapbox.navigation.core.NavigationSession.State.IDLE
import com.mapbox.navigation.core.NavigationSessionStateObserver
import com.mapbox.navigation.core.internal.accounts.MapboxNavigationAccounts
import com.mapbox.navigation.core.telemetry.events.AppMetadata
import com.mapbox.navigation.core.telemetry.events.FeedbackEvent
import com.mapbox.navigation.core.telemetry.events.MetricsRouteProgress
import com.mapbox.navigation.core.telemetry.events.NavigationArriveEvent
import com.mapbox.navigation.core.telemetry.events.NavigationCancelEvent
import com.mapbox.navigation.core.telemetry.events.NavigationDepartEvent
import com.mapbox.navigation.core.telemetry.events.NavigationEvent
import com.mapbox.navigation.core.telemetry.events.NavigationFeedbackEvent
import com.mapbox.navigation.core.telemetry.events.NavigationRerouteEvent
import com.mapbox.navigation.core.telemetry.events.PhoneState
import com.mapbox.navigation.core.telemetry.events.RerouteEvent
import com.mapbox.navigation.core.telemetry.events.SessionState
import com.mapbox.navigation.core.telemetry.events.TelemetryLocation
import com.mapbox.navigation.metrics.MapboxMetricsReporter
import com.mapbox.navigation.metrics.internal.event.NavigationAppUserTurnstileEvent
import com.mapbox.navigation.utils.internal.JobControl
import com.mapbox.navigation.utils.internal.Time
import com.mapbox.navigation.utils.internal.ifChannelException
import com.mapbox.navigation.utils.internal.ifNonNull
import com.mapbox.navigation.utils.internal.monitorChannelWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

private data class DynamicallyUpdatedRouteValues(
    val distanceRemaining: AtomicLong = AtomicLong(0),
    val timeRemaining: AtomicInteger = AtomicInteger(0),
    val rerouteCount: AtomicInteger = AtomicInteger(0),
    val routeArrived: AtomicBoolean = AtomicBoolean(false),
    val timeOfRerouteEvent: AtomicLong = AtomicLong(0),
    val timeSinceLastReroute: AtomicInteger = AtomicInteger(0),
    var sessionId: String = TelemetryUtils.obtainUniversalUniqueIdentifier(),
    val distanceCompleted: AtomicReference<Float> = AtomicReference(0f),
    val durationRemaining: AtomicInteger = AtomicInteger(0),
    val tripIdentifier: AtomicReference<String> =
        AtomicReference(TelemetryUtils.obtainUniversalUniqueIdentifier()),
    var sessionStartTime: Date = Date(),
    var sessionArrivalTime: AtomicReference<Date?> = AtomicReference(null),
    var sdkId: String = "none",
    val sessionStarted: AtomicBoolean = AtomicBoolean(false),
    val originalRoute: AtomicReference<DirectionsRoute?> = AtomicReference(null)
) {
    fun reset() {
        distanceRemaining.set(0)
        timeRemaining.set(0)
        rerouteCount.set(0)
        routeArrived.set(false)
        timeOfRerouteEvent.set(0)
        sessionId = TelemetryUtils.obtainUniversalUniqueIdentifier()
        distanceCompleted.set(0f)
        durationRemaining.set(0)
        timeSinceLastReroute.set(0)
        tripIdentifier.set(TelemetryUtils.obtainUniversalUniqueIdentifier())
        sessionArrivalTime.set(null)
        sessionStarted.set(false)
    }
}

/**
 * The one and only Telemetry class. This class handles all telemetry events.
 * Event List:
- appUserTurnstile
- navigation.depart
- navigation.feedback
- navigation.reroute
- navigation.fasterRoute
- navigation.arrive
- navigation.cancel
The class must be initialized before any telemetry events are reported. Attempting to use telemetry before initialization is called will throw an exception. Initialization may be called multiple times, the call is idempotent.
The class has two public methods, postUserFeedback() and initialize().
 */
@SuppressLint("StaticFieldLeak")
internal object MapboxNavigationTelemetry : MapboxNavigationTelemetryInterface {
    internal const val LOCATION_BUFFER_MAX_SIZE = 20
    private const val ONE_SECOND = 1000
    private const val MOCK_PROVIDER =
        "com.mapbox.navigation.core.replay.ReplayLocationEngine"

    internal const val TAG = "MAPBOX_TELEMETRY"
    private const val EVENT_VERSION = 7
    private lateinit var context: Context // Must be context.getApplicationContext
    private lateinit var telemetryThreadControl: JobControl
    private val telemetryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorSession: Job? = null
    private lateinit var metricsReporter: MetricsReporter
    private lateinit var navigationOptions: NavigationOptions
    private lateinit var localUserAgent: String
    private var weakMapboxNavigation = WeakReference<MapboxNavigation>(null)
    private var lifecycleMonitor: ApplicationLifecycleMonitor? = null
    private var appInstance: Application? = null
        set(value) {
            // Don't set it multiple times to the same value, it will cause multiple registration calls.
            if (field == value) {
                return
            }
            field = value
            ifNonNull(value) { app ->
                Log.d(TAG, "Lifecycle monitor created")
                lifecycleMonitor = ApplicationLifecycleMonitor(app)
            }
        }

    /**
     * This class holds all mutable state of the Telemetry object
     */
    private val dynamicValues = DynamicallyUpdatedRouteValues()

    private var locationEngineNameExternal: String = LocationEngine::javaClass.name

    private lateinit var callbackDispatcher: TelemetryLocationAndProgressDispatcher
    private val navigationSessionObserver = object : NavigationSessionStateObserver {
        override fun onNavigationSessionStateChanged(navigationSession: State) {
            Log.d(TAG, "Navigation state is $navigationSession")
            when (navigationSession) {
                FREE_DRIVE -> switchToNotActiveGuidanceBehavior()
                ACTIVE_GUIDANCE -> sessionStart()
                IDLE -> switchToNotActiveGuidanceBehavior()
            }
            Log.d(TAG, "Current session state is: $navigationSession")
        }
    }

    private fun switchToNotActiveGuidanceBehavior() {
        sessionEndPredicate()
        sessionEndPredicate = {}
        postUserEventDelegate = postUserEventBeforeInit
        monitorSession?.cancel()
        monitorSession = null
    }

    private fun telemetryEventGate(event: MetricEvent) {
        if (isTelemetryAvailable()) {
            Log.d(TAG, "${event::class.java} event sent")
            metricsReporter.addEvent(event)
        } else {
            Log.d(
                TAG, "${event::class.java} not sent. Caused by: " +
                    "Navigation Session started: ${dynamicValues.sessionStarted.get()}. " +
                    "Route exists: ${dynamicValues.originalRoute.get() != null}"
            )
        }
    }

// **********  EVENT OBSERVERS ***************

    //TODO do we need it? maybe just observe route from TelemetryLocationAndProgressDispatcher
    private fun populateOriginalRouteConditionally() {
//        ifNonNull(weakMapboxNavigation.get()) { mapboxNavigation ->
//            val routes = mapboxNavigation.getRoutes()
//            if (routes.isNotEmpty()) {
//                Log.d(TAG, "Getting last route from MapboxNavigation")
//                callbackDispatcher.setOriginalRoute(routes[0])
//            }
//        }
    }

    private fun sessionStart() {
        var isActiveGuidance = true
        ifNonNull(weakMapboxNavigation.get()?.getRoutes()) { routes ->
            isActiveGuidance = routes.isNotEmpty()
        }
        when (isActiveGuidance) {
            true -> {
                telemetryThreadControl.scope.launch {
                    callbackDispatcher.resetRouteProgressProcessor()
                    postUserEventDelegate =
                        postUserFeedbackEventAfterInit // Telemetry is initialized and the user selected a route. Allow user feedback events to be posted
                    handleSessionStart()
                    sessionEndPredicate = {
                        telemetryScope.launch {
                            sessionStop()
                        }
                    }
                }
            }
            false -> {
                // Do nothing
                Log.d(TAG, "Only Active Guidance supported")
            }
        }
    }

    private suspend fun sessionStop() {
        Log.d(TAG, "sessionStop")
        // The navigation session is over, disallow posting user feedback events
        postUserEventDelegate = postUserEventBeforeInit
        // Cancellation events will be sent after an arrival event.
        if (dynamicValues.routeArrived.get()) {
            telemetryScope.launch {
                Log.d(TAG, "calling processCancellationAfterArrival()")
                processCancellation()
            }.join()
        }
    }

    /**
     * The Navigation session is considered to be guided if it has been started and at least one route is active,
     * it is a free guided / idle session otherwise
     */
    private fun isTelemetryAvailable(): Boolean {
        return dynamicValues.originalRoute.get() != null && dynamicValues.sessionStarted.get()
    }

    /**
     * This method generates an off-route telemetry event. Part of the code is suspendable
     * because it waits for a new route to be offered by the SDK in response to a reroute
     */
    private fun handleOffRouteEvent() {
        telemetryThreadControl.scope.launch {
            dynamicValues.timeOfRerouteEvent.set(Time.SystemImpl.millis())
            dynamicValues.rerouteCount.addAndGet(1) // increment reroute count
            val routeProgress = callbackDispatcher.routeProgress
            val newRoute = callbackDispatcher.getDirectionsRouteChannel().receive()

            dynamicValues.distanceRemaining.set(newRoute.distance()?.toLong() ?: -1)
            dynamicValues.timeSinceLastReroute.set(
                (Time.SystemImpl.millis() - dynamicValues.timeOfRerouteEvent.get()).toInt()
            )
            callbackDispatcher.addLocationEventDescriptor(
                EventBuffers(
                    ArrayDeque(callbackDispatcher.copyLocationBuffer()),
                    ArrayDeque()
                ) { preEventBuffer, postEventBuffer ->
                    telemetryThreadControl.scope.launch {
                        // Populate the RerouteEvent
                        val rerouteEvent = RerouteEvent(populateSessionState()).apply {
                            newDistanceRemaining = newRoute.distance()?.toInt() ?: -1
                            newDurationRemaining = newRoute.duration()?.toInt() ?: -1
                            newRouteGeometry = obtainGeometry(newRoute)
                        }

                        // Populate and then send a NavigationRerouteEvent
                        val metricsRouteProgress = MetricsRouteProgress(routeProgress)
                        val navigationRerouteEvent = NavigationRerouteEvent(
                            PhoneState(context),
                            rerouteEvent,
                            metricsRouteProgress
                        ).apply {
                            locationsBefore = preEventBuffer.toTelemetryLocations().toTypedArray()
                            locationsAfter = postEventBuffer.toTelemetryLocations().toTypedArray()

                            dynamicValues.let {
                                secondsSinceLastReroute = it.timeSinceLastReroute.get() / ONE_SECOND
                                distanceRemaining = it.distanceRemaining.get().toInt()
                                distanceCompleted = it.distanceCompleted.get().toInt()
                                durationRemaining = it.durationRemaining.get()
                            }

                        }
                        populateNavigationEvent(navigationRerouteEvent)
                        telemetryEventGate(navigationRerouteEvent)
                    }
                }
            )
        }
    }

    /**
     * The lambda that is called if the SDK client did not initialize telemetry. If telemetry is not initialized,
     * calls to post a user feedback event will fail silently
     */
    private val postUserEventBeforeInit: suspend (
        String,
        String,
        String,
        String?,
        Array<String>?,
        AppMetadata?
    ) -> Unit =
        { _, _, _, _, _, _ ->
            Log.d(TAG, "Not in a navigation session, cannot send user feedback events")
        }

    /**
     * The lambda that is called once telemetry is initialized.
     */
    private val postUserFeedbackEventAfterInit: suspend (
        String,
        String,
        String,
        String?,
        Array<String>?,
        AppMetadata?
    ) -> Unit =
        { feedbackType, description, feedbackSource, screenshot, feedbackSubType, appMetadata ->
            postUserFeedbackHelper(
                feedbackType,
                description,
                feedbackSource,
                screenshot,
                feedbackSubType,
                appMetadata
            )
        }

    /**
     * The delegate lambda that dispatches either a pre or post initialization userFeedbackEvent
     */
    private var postUserEventDelegate = postUserEventBeforeInit

    private var sessionEndPredicate = { }

    fun setApplicationInstance(app: Application) {
        appInstance = app
    }

    /**
     * This method must be called before using the Telemetry object
     */
    fun initialize(
        context: Context,
        mapboxNavigation: MapboxNavigation,
        metricsReporter: MetricsReporter,
        locationEngineName: String,
        jobControl: JobControl,
        options: NavigationOptions,
        userAgent: String
    ) {
        weakMapboxNavigation.get()?.let {
            unregisterListeners(it)
            telemetryThreadControl.job.cancelChildren()
            telemetryThreadControl.job.cancel()
        }

        telemetryThreadControl = jobControl
        weakMapboxNavigation = WeakReference(mapboxNavigation)
        weakMapboxNavigation.get()?.registerNavigationSessionObserver(navigationSessionObserver)
        registerForNotification(mapboxNavigation)
        monitorOffRouteEvents()
        populateOriginalRouteConditionally()
        this.context = context
        localUserAgent = userAgent
        locationEngineNameExternal = locationEngineName
        navigationOptions = options
        this.metricsReporter = metricsReporter
        postTurnstileEvent()
        monitorJobCancellation()
        Log.i(TAG, "Valid initialization")
    }

    private fun monitorOffRouteEvents() {
        telemetryThreadControl.scope.monitorChannelWithException(
            callbackDispatcher.getOffRouteEventChannel(),
            { offRoute -> if (offRoute) handleOffRouteEvent() }
        )
    }

    private fun monitorJobCancellation() {
        telemetryScope.launch {
            select {
                telemetryThreadControl.job.onJoin {
                    Log.d(TAG, "master job canceled")
                    callbackDispatcher.flushLocationEventBuffer()
                    MapboxMetricsReporter.disable() // Disable telemetry unconditionally
                }
            }
        }
    }

    /**
     * This method sends a user feedback event to the back-end servers. The method will suspend because the helper method it calls is itself suspendable
     * The method may suspend until it collects 40 location events. The worst case scenario is a 40 location suspension, 20 is best case
     */
    override fun postUserFeedback(
        @FeedbackEvent.Type feedbackType: String,
        description: String,
        @FeedbackEvent.Source feedbackSource: String,
        screenshot: String?,
        feedbackSubType: Array<String>?,
        appMetadata: AppMetadata?
    ) {
        telemetryThreadControl.scope.launch {
            postUserEventDelegate(
                feedbackType,
                description,
                feedbackSource,
                screenshot,
                feedbackSubType,
                appMetadata
            )
        }
    }

    /**
     * Helper class that posts user feedback. The call is available only after initialization
     */
    private fun postUserFeedbackHelper(
        @FeedbackEvent.Type feedbackType: String,
        description: String,
        @FeedbackEvent.Source feedbackSource: String,
        screenshot: String?,
        feedbackSubType: Array<String>?,
        appMetadata: AppMetadata?
    ) {
        Log.d(TAG, "trying to post a user feedback event")
        val lastProgress = callbackDispatcher.routeProgress
        callbackDispatcher.addLocationEventDescriptor(
            EventBuffers(
                ArrayDeque(callbackDispatcher.copyLocationBuffer()),
                ArrayDeque()
            ) { preEventBuffer, postEventBuffer ->
                val feedbackEvent = NavigationFeedbackEvent(
                    PhoneState(context),
                    MetricsRouteProgress(lastProgress)
                ).apply {
                    this.feedbackType = feedbackType
                    this.source = feedbackSource
                    this.description = description
                    this.screenshot = screenshot
                    this.locationsBefore = preEventBuffer.toTelemetryLocations().toTypedArray()
                    this.locationsAfter = postEventBuffer.toTelemetryLocations().toTypedArray()
                    this.feedbackSubType = feedbackSubType
                    this.appMetadata = appMetadata
                }
                telemetryThreadControl.scope.launch {
                    populateNavigationEvent(feedbackEvent)
                    telemetryEventGate(feedbackEvent)
                }
            }
        )
    }

    private fun ArrayDeque<Location>.toTelemetryLocations(): List<TelemetryLocation> {
        val feedbackLocations = mutableListOf<TelemetryLocation>()
        this.forEach {
            feedbackLocations.add(
                TelemetryLocation(
                    it.latitude,
                    it.longitude,
                    it.speed,
                    it.bearing,
                    it.altitude,
                    it.time.toString(),
                    it.accuracy,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.verticalAccuracyMeters
                    } else {
                        0f
                    }
                )
            )
        }

        return feedbackLocations
    }

    /**
     * This method posts a cancel event in response to onSessionEnd
     */
    private suspend fun handleSessionCanceled() {
        val cancelEvent = NavigationCancelEvent(PhoneState(context))
        ifNonNull(dynamicValues.sessionArrivalTime.get()) {
            cancelEvent.arrivalTimestamp = TelemetryUtils.generateCreateDateFormatted(it)
        }
        populateNavigationEvent(cancelEvent)
        telemetryEventGate(cancelEvent)
        callbackDispatcher.clearLocationEventBuffer()
    }

    /**
     * This method clears the state data for the Telemetry object in response to onSessionEnd
     */
    private fun handleSessionStop() {
        dynamicValues.reset()
        callbackDispatcher.clearOriginalRoute()
    }

    /**
     * This method starts a session. If a session is active it will terminate it, causing an stop/cancel event to be sent to the servers.
     * Every session start is guaranteed to have a session end.
     */
    private fun handleSessionStart() {
        telemetryThreadControl.scope.launch {
            Log.d(TAG, "Waiting in handleSessionStart")
            dynamicValues.originalRoute.set(callbackDispatcher.originalRouteDeffered.await())
            dynamicValues.originalRoute.get()?.let { directionsRoute ->
                Log.d(TAG, "The wait is over")
                sessionStartHelper(directionsRoute)
            }
        }
    }

    /**
     * This method is used by a lambda. Since the Telemetry class is a singleton, U.I. elements may call postTurnstileEvent() before the singleton is initialized.
     * A lambda guards against this possibility
     */
    private fun postTurnstileEvent() {
        // AppUserTurnstile is implemented in mapbox-telemetry-sdk
        val sdkId = generateSdkIdentifier()
        dynamicValues.sdkId = generateSdkIdentifier()
        val appUserTurnstileEvent =
            AppUserTurnstile(sdkId, BuildConfig.MAPBOX_NAVIGATION_VERSION_NAME).also {
                it.setSkuId(
                    MapboxNavigationAccounts.getInstance(
                        context
                    ).obtainSkuId()
                )
            }
        val event = NavigationAppUserTurnstileEvent(appUserTurnstileEvent)
        metricsReporter.addEvent(event)
    }

    /**
     * This method starts a session. The start of a session does not result in a telemetry event being sent to the servers.
     */
    private fun sessionStartHelper(directionsRoute: DirectionsRoute) {
        dynamicValues.sessionId = TelemetryUtils.obtainUniversalUniqueIdentifier()
        dynamicValues.sessionStartTime = Date()
        dynamicValues.sessionStarted.set(true)
        monitorSession = telemetryThreadControl.scope.launch {
            val event = telemetryDeparture(
                directionsRoute,
                callbackDispatcher.firstLocationDeffered.await()
            )
            telemetryEventGate(event)
            monitorSession()
        }
    }

    private suspend fun processCancellation() {
        Log.d(TAG, "Session was canceled")
        handleSessionCanceled()
        handleSessionStop()
    }

    private suspend fun processArrival() {
        Log.d(TAG, "you have arrived")
        dynamicValues.tripIdentifier.set(TelemetryUtils.obtainUniversalUniqueIdentifier())
        dynamicValues.sessionArrivalTime.set(Date())
        val arriveEvent = NavigationArriveEvent(PhoneState(context))
        dynamicValues.routeArrived.set(true)
        populateNavigationEvent(arriveEvent)
        telemetryEventGate(arriveEvent)
        callbackDispatcher.clearLocationEventBuffer()
        populateOriginalRouteConditionally()
    }

    /**
     * This method waits for an [RouteProgressState.ROUTE_COMPLETE] event. Once received, it terminates the wait-loop and
     * sends the telemetry data to the servers.
     */
    private suspend fun monitorSession() {
        var continueRunning = true
        var trackingEvent = 0
        while (coroutineContext.isActive && continueRunning) {
            try {
                callbackDispatcher.getRouteProgressChannel().receive().let { progress ->
                    dynamicValues.run {
                        distanceCompleted.set(distanceCompleted.get() + progress.distanceTraveled)
                        distanceRemaining.set(progress.distanceRemaining.toLong())
                        durationRemaining.set(progress.durationRemaining.toInt())
                    }

                    when (progress.currentState) {
                        ROUTE_COMPLETE -> {
                            if (dynamicValues.sessionStarted.get()) {
                                processArrival()
                                continueRunning = false
                            } else {
                                Log.d(TAG, "route arrival received before a session start")
                            }
                        }
                        LOCATION_TRACKING -> {
                            callbackDispatcher.routeProgress?.let {
                                dynamicValues.timeRemaining.set(it.durationRemaining.toInt())
                                dynamicValues.distanceRemaining.set(it.distanceRemaining.toLong())
                            }

                            if (trackingEvent > 20) {
                                Log.i(TAG, "LOCATION_TRACKING received $trackingEvent")
                                trackingEvent = 0
                            } else {
                                trackingEvent++
                            }
                        }
                        else -> {
                            // Do nothing
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "monitorSession ${e.localizedMessage}")
                e.ifChannelException {
                    continueRunning = false
                }
            }
        }
    }

    private suspend fun telemetryDeparture(
        directionsRoute: DirectionsRoute,
        startingLocation: Location
    ): MetricEvent {
        val departEvent = NavigationDepartEvent(PhoneState(context))
        populateNavigationEvent(departEvent, directionsRoute, startingLocation)
        return departEvent
    }

    private fun registerForNotification(mapboxNavigation: MapboxNavigation) {
        callbackDispatcher =
            TelemetryLocationAndProgressDispatcher() // The class responds to most notification events
        mapboxNavigation.registerRouteProgressObserver(callbackDispatcher)
        mapboxNavigation.registerLocationObserver(callbackDispatcher)
        mapboxNavigation.registerRoutesObserver(callbackDispatcher)
        mapboxNavigation.registerOffRouteObserver(callbackDispatcher)
    }

    override fun unregisterListeners(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterRouteProgressObserver(callbackDispatcher)
        mapboxNavigation.unregisterLocationObserver(callbackDispatcher)
        mapboxNavigation.unregisterRoutesObserver(callbackDispatcher)
        mapboxNavigation.unregisterOffRouteObserver(callbackDispatcher)
        mapboxNavigation.unregisterNavigationSessionObserver(navigationSessionObserver)
        Log.d(TAG, "resetting Telemetry initialization")
        weakMapboxNavigation.clear()
    }

    private suspend fun populateSessionState(newLocation: Location? = null): SessionState {
        val timeSinceReroute: Int = if (dynamicValues.timeSinceLastReroute.get() == 0) {
            -1
        } else
            dynamicValues.timeSinceLastReroute.get()

        return SessionState().apply {
            secondsSinceLastReroute = timeSinceReroute
            eventLocation =
                newLocation ?: callbackDispatcher.lastLocation ?: Location("unknown")

            eventDate = Date()
            eventRouteDistanceCompleted =
                callbackDispatcher.routeProgress?.distanceTraveled?.toDouble() ?: 0.0
            originalDirectionRoute = callbackDispatcher.originalRouteDeffered.await()
            currentDirectionRoute =
                callbackDispatcher.routeProgress?.route
            sessionIdentifier = dynamicValues.sessionId
            tripIdentifier = dynamicValues.tripIdentifier.get()
            originalRequestIdentifier =
                callbackDispatcher.originalRouteDeffered.await().routeOptions()?.requestUuid()
            requestIdentifier =
                callbackDispatcher.routeProgress?.route?.routeOptions()?.requestUuid()
            mockLocation = locationEngineName == MOCK_PROVIDER
            rerouteCount = dynamicValues.rerouteCount.get()
            startTimestamp = dynamicValues.sessionStartTime
            arrivalTimestamp = dynamicValues.sessionArrivalTime.get()
            locationEngineName = locationEngineNameExternal
            percentInForeground = lifecycleMonitor?.obtainForegroundPercentage() ?: 100
            percentInPortrait = lifecycleMonitor?.obtainPortraitPercentage() ?: 100
        }
    }

    private suspend fun populateNavigationEvent(
        navigationEvent: NavigationEvent,
        route: DirectionsRoute? = null,
        newLocation: Location? = null
    ) {
        val directionsRoute = route ?: callbackDispatcher.routeProgress?.route
        val location = newLocation ?: callbackDispatcher.lastLocation

        navigationEvent.apply {
            sdkIdentifier = generateSdkIdentifier()

            callbackDispatcher.routeProgress?.let { routeProgress ->
                stepIndex = routeProgress.currentLegProgress?.currentStepProgress?.stepIndex ?: 0

                routeProgress.route.let {
                    geometry = it.geometry()
                    profile = it.routeOptions()?.profile()
                    requestIdentifier = it.routeOptions()?.requestUuid()
                    stepCount = obtainStepCount(it)
                    legIndex = it.routeIndex()?.toInt() ?: 0
                    legCount = it.legs()?.size ?: 0
                }
            }

            callbackDispatcher.originalRouteDeffered.await().let {
                originalStepCount = obtainStepCount(it)
                originalEstimatedDistance = it.distance()?.toInt() ?: 0
                originalEstimatedDuration = it.duration()?.toInt() ?: 0
                originalRequestIdentifier = it.routeOptions()?.requestUuid()
                originalGeometry = it.geometry()
            }

            locationEngine = locationEngineNameExternal
            tripIdentifier = TelemetryUtils.obtainUniversalUniqueIdentifier()
            lat = location?.latitude ?: 0.0
            lng = location?.longitude ?: 0.0
            simulation = locationEngineNameExternal == MOCK_PROVIDER
            percentTimeInPortrait = lifecycleMonitor?.obtainPortraitPercentage() ?: 100
            percentTimeInForeground = lifecycleMonitor?.obtainForegroundPercentage() ?: 100

            dynamicValues.let {
                startTimestamp = TelemetryUtils.generateCreateDateFormatted(it.sessionStartTime)
                distanceCompleted = it.distanceCompleted.get().toInt()
                distanceRemaining = it.distanceRemaining.get().toInt()
                durationRemaining = it.durationRemaining.get()
                rerouteCount = it.rerouteCount.get()
                sessionIdentifier = it.sessionId
            }

            eventVersion = EVENT_VERSION

            directionsRoute?.let {
                absoluteDistanceToDestination = obtainAbsoluteDistance(
                    callbackDispatcher.lastLocation,
                    obtainRouteDestination(it)
                )
                estimatedDistance = it.distance()?.toInt() ?: 0
                estimatedDuration = it.duration()?.toInt() ?: 0

                // TODO:OZ voiceIndex is not available in SDK 1.0 and was not set in the legacy telemetry        navigationEvent.voiceIndex
                // TODO:OZ bannerIndex is not available in SDK 1.0 and was not set in the legacy telemetry        navigationEvent.bannerIndex
                totalStepCount = obtainStepCount(it)
            }
        }
    }

    private fun generateSdkIdentifier() =
        if (navigationOptions.isFromNavigationUi)
            "mapbox-navigation-ui-android"
        else
            "mapbox-navigation-android"
}
