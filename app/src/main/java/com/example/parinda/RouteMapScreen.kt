package com.example.parinda

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.util.Log
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val WAYPOINTS_SOURCE_ID = "waypoints-source"
private const val WAYPOINTS_LAYER_ID = "waypoints-layer"
private const val USER_LOCATION_SOURCE_ID = "user-location-source"
private const val USER_LOCATION_LAYER_ID = "user-location-layer"
private const val TO_START_ROUTE_SOURCE_ID = "to-start-route-source"
private const val TO_START_ROUTE_LAYER_ID = "to-start-route-layer"

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
    val mapReadyState = remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    val mapReady by mapReadyState
    var lastRoutedFrom by remember { mutableStateOf<LatLng?>(null) }
    var lastRoutedAtMs by remember { mutableLongStateOf(0L) }

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

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val newLoc = LatLng(location.latitude, location.longitude)
                userLocation = newLoc
                isLoadingLocation = false
                
                // Update map source directly
                mapReady?.let { (map, style) ->
                    val userSource = style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)
                    userSource?.setGeoJson(
                        FeatureCollection.fromFeature(
                            Feature.fromGeometry(Point.fromLngLat(newLoc.longitude, newLoc.latitude))
                        )
                    )
                    // Center on user
                    map.animateCamera(CameraUpdateFactory.newLatLng(newLoc))
                }
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
                    userLocation = initialLoc
                    isLoadingLocation = false
                    mapReady?.let { (map, style) ->
                        style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)?.setGeoJson(
                            FeatureCollection.fromFeature(
                                Feature.fromGeometry(Point.fromLngLat(initialLoc.longitude, initialLoc.latitude))
                            )
                        )
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(initialLoc, 14.0))
                    }
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
        val waypointFeatures = data.stops.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))
        }
        val waypointsSource = style.getSourceAs<GeoJsonSource>(WAYPOINTS_SOURCE_ID)
        waypointsSource?.setGeoJson(FeatureCollection.fromFeatures(waypointFeatures))
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

                            // Default camera
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(20.5937, 78.9629), 3.5)
                            )

                            mapReadyState.value = map to style
                        }
                    }
                }
            }
        )

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
                val stopsText = if (gpxData!!.stops.isNotEmpty()) "${gpxData!!.stops.size} stops" else "no stops"
                Text("Route: ${gpxData!!.trackPoints.size} points, $stopsText")
                
                if (isTracking && userLocation != null) {
                    Text("📍 Tracking: ${String.format("%.5f", userLocation!!.latitude)}, ${String.format("%.5f", userLocation!!.longitude)}")
                }

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
                        Text("Start Journey")
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
                        lastRoutedFrom = null
                        lastRoutedAtMs = 0L
                        // Clear green dot and orange route from map
                        mapReady?.let { (_, style) ->
                            style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID)
                                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                            style.getSourceAs<GeoJsonSource>(TO_START_ROUTE_SOURCE_ID)
                                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                        }
                    }) {
                        Text("Stop Journey")
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
