package com.example.parinda

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val MAPBOX_MATCHING_HOST = "api.mapbox.com"

/**
 * Minimal Mapbox Map Matching client (HTTP only).
 *
 * Goal: improve visual alignment without changing the creator's route.
 *
 * To preserve route integrity, we DO NOT use the returned route geometry (which may choose
 * different roads between points). Instead, we only snap each input point individually using
 * the `tracepoints` array (same cardinality as input), and we keep the original point if the
 * snapped position is too far away.
 */
object MapboxMapMatching {
    private const val TAG = "MapboxMapMatching"

    /**
     * Mapbox Map Matching limit is ~100 coordinates per request.
     * We chunk to preserve the creator's route integrity (no downsampling).
     */
    private const val MAX_TRACE_POINTS_PER_REQUEST = 100

    suspend fun snapDrivingTrace(
        accessToken: String,
        trace: List<GpxPoint>,
        maxSnapMeters: Double = 25.0,
        httpClient: OkHttpClient = OkHttpClient()
    ): List<GpxPoint> {
        require(accessToken.isNotBlank()) { "Mapbox access token is missing" }
        require(trace.size >= 2) { "Need at least 2 trace points" }

        if (trace.size > MAX_TRACE_POINTS_PER_REQUEST) {
            Log.w(TAG, "Mapbox Matching supports ~${MAX_TRACE_POINTS_PER_REQUEST} points/request; got=${trace.size}. Skipping snapping.")
            return trace
        }

        val coords = trace.joinToString(";") { "${it.lon},${it.lat}" } // Mapbox expects lng,lat

        val url = HttpUrl.Builder()
            .scheme("https")
            .host(MAPBOX_MATCHING_HOST)
            // coords must be in the path exactly as `lng,lat;lng,lat;...`
            // Use encodedPath to avoid HttpUrl encoding ';' and ',' as path segment content.
            .encodedPath("/matching/v5/mapbox/driving/$coords")
            .addQueryParameter("overview", "false")
            .addQueryParameter("steps", "true")
            .addQueryParameter("tidy", "false")
            .addQueryParameter("access_token", accessToken)
            .build()

        Log.d(TAG, "Calling Mapbox Matching (points=${trace.size})")

        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                Log.d(TAG, "Mapbox response http=${response.code} bytes=${body.length}")
                Log.d(TAG, "Mapbox response json=${body.take(3500)}")

                if (!response.isSuccessful) {
                    throw IllegalStateException("Mapbox HTTP ${response.code}: ${body.take(300)}")
                }

                parseTracepointsResponse(body, original = trace, maxSnapMeters = maxSnapMeters)
            }
        }
    }

    private fun parseTracepointsResponse(
        json: String,
        original: List<GpxPoint>,
        maxSnapMeters: Double
    ): List<GpxPoint> {
        val root = JSONObject(json)
        val tracepoints = root.optJSONArray("tracepoints") ?: JSONArray()
        if (tracepoints.length() == 0) {
            val message = root.optString("message", "No tracepoints returned")
            throw IllegalStateException(message)
        }

        // Defensive: Mapbox should return same length as input, but keep behavior safe.
        val n = minOf(original.size, tracepoints.length())
        val snapped = ArrayList<GpxPoint>(n)
        for (i in 0 until n) {
            val orig = original[i]
            val tp = tracepoints.optJSONObject(i)
            val loc = tp?.optJSONArray("location")
            if (loc == null || loc.length() < 2) {
                snapped.add(orig)
                continue
            }

            val lng = loc.optDouble(0)
            val lat = loc.optDouble(1)
            val d = haversineMeters(orig.lat, orig.lon, lat, lng)
            if (d <= maxSnapMeters) {
                snapped.add(orig.copy(lat = lat, lon = lng))
            } else {
                snapped.add(orig)
            }
        }

        // If we didn't preserve the full list for any reason, fall back.
        return if (snapped.size >= 2) snapped else original
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
