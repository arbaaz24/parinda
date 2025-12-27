package com.example.parinda

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.util.Log
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

private const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val WAYPOINTS_SOURCE_ID = "waypoints-source"
private const val WAYPOINTS_LAYER_ID = "waypoints-layer"
private const val USER_LOCATION_SOURCE_ID = "user-location-source"
private const val USER_LOCATION_LAYER_ID = "user-location-layer"
private const val TO_START_ROUTE_SOURCE_ID = "to-start-route-source"
private const val TO_START_ROUTE_LAYER_ID = "to-start-route-layer"
private const val NAV_ARROW_SOURCE_ID = "nav-arrow-source"
private const val NAV_ARROW_LAYER_ID = "nav-arrow-layer"
private const val NAV_ARROW_IMAGE_ID = "nav-arrow-image"
private const val ROUTE_ARROWS_SOURCE_ID = "route-arrows-source"
private const val ROUTE_ARROWS_LAYER_ID = "route-arrows-layer"

private const val ROUTE_ARROW_SPACING_METERS = 100f

private const val NAV_TRIGGER_METERS = 5f
private const val NAV_ZOOM_LEVEL = 17.0

@Composable
fun RouteMapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // State
    var gpxData by remember { mutableStateOf<GpxData?>(null) }
    var gpxError by remember { mutableStateOf<String?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var wantsToTrack by remember { mutableStateOf(false) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var isLoadingLocation by remember { mutableStateOf(false) }
    var isNavigationMode by remember { mutableStateOf(false) }
    var isFollowingUser by remember { mutableStateOf(true) }
    var lastBearingFrom by remember { mutableStateOf<LatLng?>(null) }
    var selectedCallout by remember { mutableStateOf<Pair<LatLng, String>?>(null) }
    var selectedCalloutScreenPoint by remember { mutableStateOf<PointF?>(null) }
    val mapReadyState = remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    val mapReady by mapReadyState
    var lastRoutedFrom by remember { mutableStateOf<LatLng?>(null) }
    var lastRoutedAtMs by remember { mutableLongStateOf(0L) }

    val latestGpxData by rememberUpdatedState(gpxData)
    val latestIsNavigationMode by rememberUpdatedState(isNavigationMode)

    // Permission state
    var hasLocationPermission by remember { mutableStateOf(false) }
    var showLocationSettingsDialog by remember { mutableStateOf(false) }

    // Check if location services are enabled
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Launcher to open location settings
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // When user returns from settings, check if they enabled location
        if (isLocationEnabled() && hasLocationPermission && wantsToTrack) {
            isTracking = true
            isLoadingLocation = true
            // Standard flow: the tracking DisposableEffect registers location updates and
            // bootstraps with last-known location when possible.
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        if (granted && wantsToTrack) {
            if (isLocationEnabled()) {
                isTracking = true
            } else {
                showLocationSettingsDialog = true
            }
        }
    }

    // File picker - restricted to GPX/XML files
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                gpxError = null
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    gpxData = parseGpx(inputStream)
                }
            } catch (e: Exception) {
                gpxData = null
                gpxError = "Failed to parse GPX file: ${e.message}"
                Log.e("RouteMapScreen", "GPX parsing error", e)
            }
        }
    }

    val mapView = remember {
        MapView(context).apply {
            contentDescription = "map"
        }
    }

    // Lifecycle bridge
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // GPS tracking effect
    DisposableEffect(isTracking, hasLocationPermission) {
        if (!isTracking || !hasLocationPermission) {
            return@DisposableEffect onDispose { }
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var activeListener: LocationListener? = null

        fun updateUserAndMaybeNavigate(newLoc: LatLng, bearingDeg: Float?) {
            userLocation = newLoc
            isLoadingLocation = false

            val data = latestGpxData
            val start = data?.startPoint?.let { LatLng(it.lat, it.lon) }

            if (!isNavigationMode && data != null && start != null && data.trackPoints.isNotEmpty()) {
                val nearStart = distanceMeters(newLoc, start) <= NAV_TRIGGER_METERS
                val routeDistance = distanceToRouteMeters(newLoc, data.trackPoints)
                val nearRoute = routeDistance != null && routeDistance <= NAV_TRIGGER_METERS

                if (nearStart || nearRoute) {
                    isNavigationMode = true
                    isFollowingUser = true
                    Log.d("RouteMapScreen", "Entering navigation mode (nearStart=$nearStart nearRoute=$nearRoute distToRoute=$routeDistance)")
                }
            }

            mapReady?.let { (map, style) ->
                style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)
                    ?.setGeoJson(
                        FeatureCollection.fromFeature(
                            Feature.fromGeometry(Point.fromLngLat(newLoc.longitude, newLoc.latitude))
                        )
                    )

                // Arrow marker (navigation mode)
                if (isNavigationMode) {
                    val feature = Feature.fromGeometry(Point.fromLngLat(newLoc.longitude, newLoc.latitude))
                    val bearingToUse = bearingDeg ?: 0f
                    feature.addNumberProperty("bearing", bearingToUse.toDouble())
                    style.getSourceAs<GeoJsonSource>(NAV_ARROW_SOURCE_ID)
                        ?.setGeoJson(FeatureCollection.fromFeature(feature))

                    if (isFollowingUser) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(newLoc, NAV_ZOOM_LEVEL))
                    }
                } else {
                    // Default behavior (non-navigation): keep centering like before.
                    map.animateCamera(CameraUpdateFactory.newLatLng(newLoc))
                }
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val newLoc = LatLng(location.latitude, location.longitude)
                val bearingDeg = when {
                    location.hasBearing() -> location.bearing
                    lastBearingFrom != null -> bearingDegrees(lastBearingFrom!!, newLoc)
                    else -> null
                }
                lastBearingFrom = newLoc

                updateUserAndMaybeNavigate(newLoc, bearingDeg)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            @SuppressLint("MissingPermission")
            fun startUpdates() {
                // Immediately try to get last known location from any provider for fast green dot
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                var bestLoc: Location? = null
                for (p in providers) {
                    try {
                        val loc = locationManager.getLastKnownLocation(p)
                        if (loc != null && (bestLoc == null || loc.time > bestLoc!!.time)) {
                            bestLoc = loc
                        }
                    } catch (_: Exception) { }
                }
                bestLoc?.let { loc ->
                    val initialLoc = LatLng(loc.latitude, loc.longitude)
                    lastBearingFrom = initialLoc
                    updateUserAndMaybeNavigate(initialLoc, loc.bearing.takeIf { loc.hasBearing() })
                }

                // Network-first (fast), then GPS (accurate). Register both if available.
                var requestedAny = false
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    // A single quick update helps on cold start.
                    locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null)
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, listener)
                    requestedAny = true
                }
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
                    requestedAny = true
                }
                if (requestedAny) {
                    activeListener = listener
                }
            }
            startUpdates()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        onDispose {
            activeListener?.let { locationManager.removeUpdates(it) }
        }
    }

    // Update map when gpxData or userLocation changes
    LaunchedEffect(gpxData, mapReady) {
        val (map, style) = mapReady ?: return@LaunchedEffect
        val data = gpxData ?: return@LaunchedEffect

        // Route line
        val routePoints = data.trackPoints.map { Point.fromLngLat(it.lon, it.lat) }
        if (routePoints.isNotEmpty()) {
            val lineString = LineString.fromLngLats(routePoints)
            val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            routeSource?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(lineString)))

            // Zoom to fit
            val boundsBuilder = LatLngBounds.Builder()
            data.trackPoints.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
            data.stops.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
        }

        // Stops (red circles)
        val waypointFeatures = data.stops.map { stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.lon, stop.lat)).apply {
                addStringProperty("desc", stop.desc ?: stop.name ?: "")
            }
        }
        val waypointsSource = style.getSourceAs<GeoJsonSource>(WAYPOINTS_SOURCE_ID)
        waypointsSource?.setGeoJson(FeatureCollection.fromFeatures(waypointFeatures))

        // Route direction arrows
        val arrowFeatures = buildRouteArrowFeatures(data.trackPoints, ROUTE_ARROW_SPACING_METERS)
        style.getSourceAs<GeoJsonSource>(ROUTE_ARROWS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(arrowFeatures))
    }

    // MVP: online routing from current location -> GPX start point (shown if same country)
    LaunchedEffect(isTracking, userLocation, gpxData, mapReady) {
        if (!isTracking) return@LaunchedEffect

        val (_, style) = mapReady ?: return@LaunchedEffect
        val data = gpxData ?: return@LaunchedEffect
        val from = userLocation ?: return@LaunchedEffect

        val start = data.startPoint?.let { LatLng(it.lat, it.lon) }
            ?: return@LaunchedEffect

        val toStartSource = style.getSourceAs<GeoJsonSource>(TO_START_ROUTE_SOURCE_ID)

        Log.d("RouteMapScreen", "To-start routing: from=${from.latitude},${from.longitude} to start=${start.latitude},${start.longitude}")

        // NOTE: Removed reverse-geocoding country check.
        // Geocoder can block the main thread on some devices/emulators and delay *all* location/UI updates.
        // For MVP we always draw the helper line when tracking + GPX are present.

        val now = System.currentTimeMillis()
        val lastFrom = lastRoutedFrom
        val shouldReroute = lastFrom == null ||
            (now - lastRoutedAtMs) >= 15_000L ||
            distanceMeters(lastFrom, from) >= 50f

        if (!shouldReroute) return@LaunchedEffect

        // MVP: Draw a straight line from current → start ("as the crow flies")
        // This avoids dependency on external routing services (OSRM/Valhalla)
        Log.d("RouteMapScreen", "Drawing straight line to start")
        val lineString = LineString.fromLngLats(
            listOf(
                Point.fromLngLat(from.longitude, from.latitude),
                Point.fromLngLat(start.longitude, start.latitude)
            )
        )

        val geo = FeatureCollection.fromFeature(Feature.fromGeometry(lineString))
        toStartSource?.setGeoJson(geo)
        lastRoutedFrom = from
        lastRoutedAtMs = now
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { _ ->
                mapView.also { view ->
                    view.getMapAsync { map ->
                        map.setStyle(DEFAULT_STYLE_URL) { style ->
                            // Add sources if not present
                            if (style.getSource(ROUTE_SOURCE_ID) == null) {
                                style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                            }
                            if (style.getSource(WAYPOINTS_SOURCE_ID) == null) {
                                style.addSource(GeoJsonSource(WAYPOINTS_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                            }
                            if (style.getSource(USER_LOCATION_SOURCE_ID) == null) {
                                style.addSource(GeoJsonSource(USER_LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                            }
                            if (style.getSource(TO_START_ROUTE_SOURCE_ID) == null) {
                                style.addSource(GeoJsonSource(TO_START_ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                            }
                            if (style.getSource(NAV_ARROW_SOURCE_ID) == null) {
                                style.addSource(GeoJsonSource(NAV_ARROW_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                            }
                            if (style.getSource(ROUTE_ARROWS_SOURCE_ID) == null) {
                                style.addSource(GeoJsonSource(ROUTE_ARROWS_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
                            }

                            // Route layer (blue line)
                            if (style.getLayer(ROUTE_LAYER_ID) == null) {
                                style.addLayer(
                                    LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                                        lineColor(Color.BLUE),
                                        lineWidth(5f),
                                        lineCap(Property.LINE_CAP_ROUND),
                                        lineJoin(Property.LINE_JOIN_ROUND)
                                    )
                                )
                            }

                            // Route arrows layer (direction along the route)
                            if (style.getImage(NAV_ARROW_IMAGE_ID) == null) {
                                style.addImage(NAV_ARROW_IMAGE_ID, createNavArrowBitmap())
                            }
                            if (style.getLayer(ROUTE_ARROWS_LAYER_ID) == null) {
                                style.addLayer(
                                    SymbolLayer(ROUTE_ARROWS_LAYER_ID, ROUTE_ARROWS_SOURCE_ID).withProperties(
                                        iconImage(NAV_ARROW_IMAGE_ID),
                                        iconAllowOverlap(true),
                                        iconIgnorePlacement(true),
                                        iconSize(0.55f),
                                        iconRotate(Expression.get("bearing")),
                                        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
                                    )
                                )
                            }

                            // Waypoints layer (red circles)
                            if (style.getLayer(WAYPOINTS_LAYER_ID) == null) {
                                style.addLayer(
                                    CircleLayer(WAYPOINTS_LAYER_ID, WAYPOINTS_SOURCE_ID).withProperties(
                                        circleRadius(8f),
                                        circleColor(Color.RED),
                                        circleStrokeWidth(2f),
                                        circleStrokeColor(Color.WHITE)
                                    )
                                )
                            }

                            // User location layer (green circle)
                            if (style.getLayer(USER_LOCATION_LAYER_ID) == null) {
                                style.addLayer(
                                    CircleLayer(USER_LOCATION_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                                        circleRadius(10f),
                                        circleColor(Color.GREEN),
                                        circleStrokeWidth(3f),
                                        circleStrokeColor(Color.WHITE)
                                    )
                                )
                            }

                            // Navigation arrow layer (only populated in navigation mode)
                            if (style.getLayer(NAV_ARROW_LAYER_ID) == null) {
                                style.addLayer(
                                    SymbolLayer(NAV_ARROW_LAYER_ID, NAV_ARROW_SOURCE_ID).withProperties(
                                        iconImage(NAV_ARROW_IMAGE_ID),
                                        iconAllowOverlap(true),
                                        iconIgnorePlacement(true),
                                        iconSize(1.0f),
                                        iconRotate(Expression.get("bearing"))
                                    )
                                )
                            }

                            // MVP: online "to start" route layer (orange)
                            if (style.getLayer(TO_START_ROUTE_LAYER_ID) == null) {
                                style.addLayer(
                                    LineLayer(TO_START_ROUTE_LAYER_ID, TO_START_ROUTE_SOURCE_ID).withProperties(
                                        lineColor(Color.parseColor("#FF8C00")),
                                        lineWidth(4f),
                                        lineCap(Property.LINE_CAP_ROUND),
                                        lineJoin(Property.LINE_JOIN_ROUND),
                                        lineOpacity(0.9f)
                                    )
                                )
                            }

                            // Click listener for waypoints
                            map.addOnMapClickListener { point ->
                                val screenPoint = map.projection.toScreenLocation(point)
                                // Use a 20px tap tolerance box
                                val tolerance = 20f
                                val box = RectF(
                                    screenPoint.x - tolerance,
                                    screenPoint.y - tolerance,
                                    screenPoint.x + tolerance,
                                    screenPoint.y + tolerance
                                )
                                val features = map.queryRenderedFeatures(box, WAYPOINTS_LAYER_ID)
                                Log.d("RouteMapScreen", "Tap detected, features found: ${features.size}")

                                if (features.isNotEmpty()) {
                                    val feature = features.first()
                                    val desc = feature.getStringProperty("desc") ?: ""
                                    Log.d("RouteMapScreen", "Waypoint tapped, desc: $desc")

                                    val geometry = feature.geometry()
                                    if (geometry is Point) {
                                        val displayText = desc.ifEmpty { "Waypoint" }
                                        val calloutLatLng = LatLng(geometry.latitude(), geometry.longitude())
                                        selectedCallout = calloutLatLng to displayText
                                        selectedCalloutScreenPoint = map.projection.toScreenLocation(calloutLatLng)
                                    }
                                    return@addOnMapClickListener true
                                }

                                // Clicked elsewhere: dismiss callout
                                if (selectedCallout != null) {
                                    selectedCallout = null
                                    selectedCalloutScreenPoint = null
                                }
                                return@addOnMapClickListener false
                            }

                            // Keep the callout anchored to the tapped waypoint while the map moves/rotates.
                            map.addOnCameraMoveListener {
                                selectedCallout?.first?.let { latLng ->
                                    selectedCalloutScreenPoint = map.projection.toScreenLocation(latLng)
                                }
                            }

                            // Default camera
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(20.5937, 78.9629), 3.5)
                            )

                            // If the user pans/zooms the map during navigation mode, stop auto-follow.
                            map.addOnCameraMoveStartedListener { reason ->
                                if (latestIsNavigationMode &&
                                    reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                                ) {
                                    isFollowingUser = false
                                }
                            }

                            mapReadyState.value = map to style
                        }
                    }
                }
            }
        )

        // In-map callout (Compose overlay). This is not a system popup and can later host images too.
        val callout = selectedCallout
        val calloutPoint = selectedCalloutScreenPoint
        if (callout != null && calloutPoint != null) {
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = calloutPoint.x.roundToInt() - 220,
                            y = calloutPoint.y.roundToInt() - 180
                        )
                    }
                    .clickable {
                        selectedCallout = null
                        selectedCalloutScreenPoint = null
                    },
                tonalElevation = 2.dp
            ) {
                Text(
                    text = callout.second,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Buttons overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { 
                gpxError = null
                filePickerLauncher.launch("*/*") 
            }) {
                Text("Load GPX")
            }

            // Show error if GPX parsing failed
            if (gpxError != null) {
                Text(
                    text = gpxError!!,
                    color = androidx.compose.ui.graphics.Color.Red,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (gpxData != null) {
                Text("${gpxData!!.stops.size} stop(s)")

                if (!isTracking) {
                    Button(onClick = {
                        wantsToTrack = true
                        if (hasLocationPermission) {
                            // Already have permission, check if location is enabled
                            if (isLocationEnabled()) {
                                isTracking = true
                                isLoadingLocation = true
                            } else {
                                showLocationSettingsDialog = true
                            }
                        } else {
                            // Request permission; callback will start tracking if granted
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    }) {
                        Text("Go to chosen route")
                    }
                } else {
                    // Show loading indicator when waiting for location
                    if (isLoadingLocation) {
                        Text("⏳ Getting your location...")
                    }
                    
                    Button(onClick = {
                        isTracking = false
                        wantsToTrack = false
                        userLocation = null
                        isLoadingLocation = false
                        isNavigationMode = false
                        isFollowingUser = true
                        lastBearingFrom = null
                        lastRoutedFrom = null
                        lastRoutedAtMs = 0L
                        // Clear green dot and orange route from map
                        mapReady?.let { (_, style) ->
                            style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)
                                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                            style.getSourceAs<GeoJsonSource>(TO_START_ROUTE_SOURCE_ID)
                                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                            style.getSourceAs<GeoJsonSource>(NAV_ARROW_SOURCE_ID)
                                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                        }
                    }) {
                        Text("Stop")
                    }

                    if (isNavigationMode) {
                        Button(onClick = {
                            isNavigationMode = false
                            isFollowingUser = true
                            mapReady?.let { (_, style) ->
                                style.getSourceAs<GeoJsonSource>(NAV_ARROW_SOURCE_ID)
                                    ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                            }
                        }) {
                            Text("Exit Navigation")
                        }

                        Button(onClick = {
                            isFollowingUser = true
                            userLocation?.let { loc ->
                                mapReady?.let { (map, _) ->
                                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, NAV_ZOOM_LEVEL))
                                }
                            }
                        }) {
                            Text("Re-center")
                        }
                    }
                }
            }
        }

        // Location settings dialog
        if (showLocationSettingsDialog) {
            AlertDialog(
                onDismissRequest = {
                    showLocationSettingsDialog = false
                    wantsToTrack = false
                },
                title = { Text("Location Required") },
                text = { Text("Please enable location services to track your position on the route.") },
                confirmButton = {
                    TextButton(onClick = {
                        showLocationSettingsDialog = false
                        locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showLocationSettingsDialog = false
                        wantsToTrack = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun distanceMeters(a: LatLng, b: LatLng): Float {
    val results = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return results[0]
}

private fun bearingDegrees(from: LatLng, to: LatLng): Float {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = Math.sin(dLon) * Math.cos(lat2)
    val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
    val brng = Math.toDegrees(Math.atan2(y, x))
    val normalized = (brng + 360.0) % 360.0
    return normalized.toFloat()
}

private fun distanceToRouteMeters(user: LatLng, trackPoints: List<GpxPoint>): Float? {
    if (trackPoints.size < 2) return null

    val r = 6_371_000.0 // meters
    val lat0 = Math.toRadians(user.latitude)
    val lon0 = Math.toRadians(user.longitude)
    val cosLat0 = Math.cos(lat0)

    fun toLocalMeters(p: GpxPoint): Pair<Double, Double> {
        val lat = Math.toRadians(p.lat)
        val lon = Math.toRadians(p.lon)
        val dx = (lon - lon0) * cosLat0 * r
        val dy = (lat - lat0) * r
        return dx to dy
    }

    var minDistSq = Double.POSITIVE_INFINITY
    var prev = toLocalMeters(trackPoints[0])
    for (i in 1 until trackPoints.size) {
        val cur = toLocalMeters(trackPoints[i])
        val x1 = prev.first
        val y1 = prev.second
        val x2 = cur.first
        val y2 = cur.second
        val vx = x2 - x1
        val vy = y2 - y1
        val lenSq = vx * vx + vy * vy

        val t = if (lenSq <= 0.0) 0.0 else {
            // User point is origin (0,0); projection of origin onto segment
            val proj = (-(x1 * vx + y1 * vy)) / lenSq
            proj.coerceIn(0.0, 1.0)
        }

        val cx = x1 + t * vx
        val cy = y1 + t * vy
        val distSq = cx * cx + cy * cy
        if (distSq < minDistSq) minDistSq = distSq
        prev = cur
    }
    return Math.sqrt(minDistSq).toFloat()
}

private fun buildRouteArrowFeatures(trackPoints: List<GpxPoint>, spacingMeters: Float): List<Feature> {
    if (trackPoints.size < 2) return emptyList()
    if (spacingMeters <= 0f) return emptyList()

    val features = ArrayList<Feature>()
    val maxArrows = 500

    fun segmentDistanceMeters(a: GpxPoint, b: GpxPoint): Float {
        val results = FloatArray(1)
        Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, results)
        return results[0]
    }

    fun interpolate(a: GpxPoint, b: GpxPoint, t: Float): LatLng {
        val lat = a.lat + (b.lat - a.lat) * t
        val lon = a.lon + (b.lon - a.lon) * t
        return LatLng(lat, lon)
    }

    var carry = 0f
    var prev = trackPoints[0]
    for (i in 1 until trackPoints.size) {
        val next = trackPoints[i]
        var segDist = segmentDistanceMeters(prev, next)
        if (segDist <= 0f) {
            prev = next
            continue
        }

        val segBearing = bearingDegrees(LatLng(prev.lat, prev.lon), LatLng(next.lat, next.lon))

        var localPrev = prev
        while (carry + segDist >= spacingMeters) {
            if (features.size >= maxArrows) return features

            val remaining = spacingMeters - carry
            val t = (remaining / segDist).coerceIn(0f, 1f)
            val p = interpolate(localPrev, next, t)

            val f = Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude))
            f.addNumberProperty("bearing", segBearing.toDouble())
            features.add(f)

            // Continue on the same segment from this inserted point
            carry = 0f
            localPrev = GpxPoint(p.latitude, p.longitude)
            segDist -= remaining
            if (segDist <= 0f) break
        }

        carry += segDist
        prev = next
    }

    return features
}

private fun createNavArrowBitmap(): Bitmap {
    // Simple white arrow/triangle pointing "up" (0°). Rotation is handled by the SymbolLayer.
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.BLACK
    }

    val path = Path().apply {
        moveTo(size / 2f, 6f)
        lineTo(10f, size - 10f)
        lineTo(size - 10f, size - 10f)
        close()
    }

    canvas.drawPath(path, paint)
    canvas.drawPath(path, stroke)
    return bmp
}
