package com.example.parinda

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val MAPBOX_MATCHING_HOST = "api.mapbox.com"

/**
 * Mapbox Map Matching client (HTTP only).
 *
 * Goal: improve visual alignment WITHOUT changing the creator's route.
 * We use tracepoints (1:1 with input) to snap each point individually,
 * preserving the original route structure. We also extract navigation
 * directions for turn-by-turn guidance during riding.
 *
 * Supports large GPX files by chunking requests with parallel processing,
 * retry logic, and proper timeout configuration.
 */
object MapboxMapMatching {
    private const val TAG = "MapboxMapMatching"

    /**
     * Mapbox Map Matching limit is 100 coordinates per request.
     */
    private const val MAX_COORDS_PER_REQUEST = 100
    private const val MAX_SNAP_METERS = 25.0  // Don't snap if snapped point is too far from original
    private const val MAX_PARALLEL_REQUESTS = 6  // Limit concurrent requests to avoid rate limiting
    private const val MAX_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 500L
    private const val MAX_TOTAL_POINTS = 10_000  // Skip matching for very large GPX files

    // Singleton OkHttpClient with proper timeouts
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Snap trace points to roads and extract navigation directions.
     * @param onProgress Called with (currentChunk, totalChunks) as each chunk completes.
     * @return MatchedRoute with snapped points and navigation instructions
     */
    suspend fun snapDrivingTrace(
        accessToken: String,
        trace: List<GpxPoint>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): MatchedRoute {
        require(accessToken.isNotBlank()) { "Mapbox access token is missing" }
        require(trace.size >= 2) { "Need at least 2 trace points" }

        // Skip matching for very large GPX files to avoid excessive API calls
        if (trace.size > MAX_TOTAL_POINTS) {
            Log.d(TAG, "Skipping map matching: ${trace.size} points exceeds limit of $MAX_TOTAL_POINTS")
            return MatchedRoute(trace, emptyList())
        }

        Log.d(TAG, "Starting map matching: totalPoints=${trace.size}")

        // Split into chunks (no overlap needed since we snap 1:1)
        val chunks = trace.chunked(MAX_COORDS_PER_REQUEST)
        val totalChunks = chunks.size
        Log.d(TAG, "Split into $totalChunks chunk(s)")

        // Semaphore to limit parallel requests
        val semaphore = Semaphore(MAX_PARALLEL_REQUESTS)
        var completedCount = 0
        val lock = Any()

        // Process chunks in parallel with limited concurrency
        val results = coroutineScope {
            chunks.mapIndexed { index, chunk ->
                async {
                    semaphore.withPermit {
                        val result = fetchAndSnapChunkWithRetry(
                            accessToken = accessToken,
                            traceChunk = chunk,
                            chunkIndex = index,
                            totalChunks = totalChunks
                        )
                        // Update progress
                        synchronized(lock) {
                            completedCount++
                            onProgress?.invoke(completedCount, totalChunks)
                        }
                        index to result  // Keep order
                    }
                }
            }.awaitAll()
        }

        // Sort by index and merge results
        val sortedResults = results.sortedBy { it.first }
        val snapped = sortedResults.flatMap { it.second.first }
        val instructions = sortedResults.flatMap { it.second.second }

        Log.d(TAG, "Finished map matching: snappedPoints=${snapped.size}, instructions=${instructions.size}")
        
        val finalPoints = if (snapped.size >= 2) snapped else trace
        return MatchedRoute(finalPoints, instructions)
    }

    private suspend fun fetchAndSnapChunkWithRetry(
        accessToken: String,
        traceChunk: List<GpxPoint>,
        chunkIndex: Int,
        totalChunks: Int
    ): Pair<List<GpxPoint>, List<NavigationInstruction>> {
        var delayMs = INITIAL_RETRY_DELAY_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                return fetchAndSnapChunk(accessToken, traceChunk, chunkIndex, totalChunks)
            } catch (e: Exception) {
                val isRateLimit = e.message?.contains("429") == true
                val isServerError = e.message?.contains("5") == true && e.message?.contains("00") == true

                if (attempt < MAX_RETRIES - 1 && (isRateLimit || isServerError)) {
                    Log.w(TAG, "chunk ${chunkIndex + 1}/$totalChunks retry ${attempt + 1} after ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                    delayMs *= 2  // Exponential backoff
                } else {
                    Log.e(TAG, "chunk ${chunkIndex + 1}/$totalChunks failed after ${attempt + 1} attempts", e)
                }
            }
        }

        // All retries failed, return original chunk
        Log.w(TAG, "chunk ${chunkIndex + 1}/$totalChunks returning original after all retries failed")
        return traceChunk to emptyList()
    }

    private suspend fun fetchAndSnapChunk(
        accessToken: String,
        traceChunk: List<GpxPoint>,
        chunkIndex: Int,
        totalChunks: Int
    ): Pair<List<GpxPoint>, List<NavigationInstruction>> {
        if (traceChunk.size < 2) return traceChunk to emptyList()

        val coords = traceChunk.joinToString(";") { "${it.lon},${it.lat}" }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host(MAPBOX_MATCHING_HOST)
            .encodedPath("/matching/v5/mapbox/driving/$coords")
            .addQueryParameter("tidy", "false")  // Keep 1:1 mapping with input
            .addQueryParameter("steps", "true")  // Get turn-by-turn directions
            .addQueryParameter("access_token", accessToken)
            .build()

        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "chunk ${chunkIndex + 1}/$totalChunks http=${response.code} inPts=${traceChunk.size}")
                }

                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: ${body.take(200)}")
                }

                val snapped = parseTracepoints(body, traceChunk, chunkIndex, totalChunks)
                val instructions = parseInstructions(body, chunkIndex, totalChunks)
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "chunk ${chunkIndex + 1}/$totalChunks snapped=${snapped.size} instructions=${instructions.size}")
                }
                
                snapped to instructions
            }
        }
    }

    /**
     * Parse tracepoints array - same length as input, snap each point individually.
     * If snapped location is too far from original, keep the original point.
     */
    private fun parseTracepoints(
        json: String,
        original: List<GpxPoint>,
        chunkIndex: Int,
        totalChunks: Int
    ): List<GpxPoint> {
        val root = JSONObject(json)
        val tracepoints = root.optJSONArray("tracepoints")
        
        if (tracepoints == null || tracepoints.length() == 0) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "chunk ${chunkIndex + 1}/$totalChunks No tracepoints: ${root.optString("message", "unknown")}")
            }
            return original
        }

        val n = minOf(original.size, tracepoints.length())
        val snapped = ArrayList<GpxPoint>(n)
        
        for (i in 0 until n) {
            val orig = original[i]
            val tp = tracepoints.optJSONObject(i)
            val loc = tp?.optJSONArray("location")
            
            if (loc == null || loc.length() < 2) {
                // Mapbox couldn't match this point, keep original
                snapped.add(orig)
                continue
            }

            val lng = loc.getDouble(0)
            val lat = loc.getDouble(1)
            val distance = haversineMeters(orig.lat, orig.lon, lat, lng)
            
            if (distance <= MAX_SNAP_METERS) {
                // Snap to road
                snapped.add(orig.copy(lat = lat, lon = lng))
            } else {
                // Too far, keep original (preserves creator's route)
                snapped.add(orig)
            }
        }

        return snapped
    }

    /**
     * Parse navigation instructions from matchings[].legs[].steps[]
     */
    private fun parseInstructions(
        json: String,
        chunkIndex: Int,
        totalChunks: Int
    ): List<NavigationInstruction> {
        val root = JSONObject(json)
        val matchings = root.optJSONArray("matchings") ?: return emptyList()
        
        if (matchings.length() == 0) return emptyList()
        
        val instructions = ArrayList<NavigationInstruction>()
        
        // Iterate through all matchings (usually just one per chunk)
        for (m in 0 until matchings.length()) {
            val matching = matchings.optJSONObject(m) ?: continue
            val legs = matching.optJSONArray("legs") ?: continue
            
            for (l in 0 until legs.length()) {
                val leg = legs.optJSONObject(l) ?: continue
                val steps = leg.optJSONArray("steps") ?: continue
                
                for (s in 0 until steps.length()) {
                    val step = steps.optJSONObject(s) ?: continue
                    val maneuver = step.optJSONObject("maneuver") ?: continue
                    
                    val instruction = maneuver.optString("instruction", "")
                    val maneuverType = maneuver.optString("type", "")
                    val modifier = maneuver.optString("modifier", null)
                    val location = maneuver.optJSONArray("location")
                    
                    // Skip empty or depart/arrive for middle chunks
                    if (instruction.isBlank()) continue
                    if (chunkIndex > 0 && maneuverType == "depart") continue
                    if (chunkIndex < totalChunks - 1 && maneuverType == "arrive") continue
                    
                    val lng = location?.optDouble(0) ?: continue
                    val lat = location?.optDouble(1) ?: continue
                    
                    instructions.add(
                        NavigationInstruction(
                            instruction = instruction,
                            maneuverType = maneuverType,
                            modifier = modifier,
                            distanceMeters = step.optDouble("distance", 0.0),
                            durationSeconds = step.optDouble("duration", 0.0),
                            location = GpxPoint(lat = lat, lon = lng)
                        )
                    )
                }
            }
        }
        
        return instructions
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
