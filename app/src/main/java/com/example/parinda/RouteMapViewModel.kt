package com.example.parinda

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class RouteMapViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    var gpxData: GpxData? by mutableStateOf(null)
        private set

    var matchedTrackPoints: List<GpxPoint>? by mutableStateOf(null)
        private set

    var navigationInstructions: List<NavigationInstruction> by mutableStateOf(emptyList())
        private set

    var gpxError: String? by mutableStateOf(null)
        private set

    var matchingProgress: Pair<Int, Int>? by mutableStateOf(null)
        private set

    private var inMemoryGpxBytes: ByteArray? = null

    private var gpxCacheKey: String? = null

    private var gpxUriString: String?
        get() = savedStateHandle[KEY_GPX_URI]
        set(value) {
            savedStateHandle[KEY_GPX_URI] = value
        }

    fun onGpxSelected(uri: Uri, contentResolver: ContentResolver, context: Context) {
        val appContext = context.applicationContext
        gpxUriString = uri.toString()
        gpxError = null
        matchedTrackPoints = null
        navigationInstructions = emptyList()
        matchingProgress = null
        inMemoryGpxBytes = null
        gpxCacheKey = null

        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Unable to open GPX")
                }
                inMemoryGpxBytes = bytes
                gpxCacheKey = withContext(Dispatchers.Default) {
                    SnapCache.gpxBytesToCacheKeySha256Hex(bytes)
                }
                gpxData = withContext(Dispatchers.Default) {
                    parseGpx(ByteArrayInputStream(bytes))
                }

                // If we already have a cached snap for this GPX, use it immediately.
                val key = gpxCacheKey
                if (key != null) {
                    val cached = withContext(Dispatchers.IO) { SnapCache.load(appContext, key) }
                    if (cached != null) {
                        matchedTrackPoints = cached.snappedPoints
                        navigationInstructions = cached.instructions
                    }
                }
            } catch (e: Exception) {
                gpxData = null
                matchedTrackPoints = null
                navigationInstructions = emptyList()
                gpxError = "Failed to parse GPX file: ${e.message}"
            }
        }
    }

    fun clearGpxError() {
        gpxError = null
    }

    fun restoreIfNeeded(contentResolver: ContentResolver, context: Context) {
        val appContext = context.applicationContext
        if (gpxData != null) return

        val bytes = inMemoryGpxBytes
        if (bytes != null && bytes.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    gpxCacheKey = withContext(Dispatchers.Default) {
                        SnapCache.gpxBytesToCacheKeySha256Hex(bytes)
                    }
                    gpxData = withContext(Dispatchers.Default) {
                        parseGpx(ByteArrayInputStream(bytes))
                    }

                    val key = gpxCacheKey
                    if (key != null) {
                        val cached = withContext(Dispatchers.IO) { SnapCache.load(appContext, key) }
                        if (cached != null) {
                            matchedTrackPoints = cached.snappedPoints
                            navigationInstructions = cached.instructions
                        }
                    }
                } catch (e: Exception) {
                    gpxData = null
                    gpxError = "Failed to restore GPX: ${e.message}"
                }
            }
            return
        }

        val uriStr = gpxUriString ?: return
        viewModelScope.launch {
            try {
                val uri = Uri.parse(uriStr)
                val loaded = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Unable to open GPX")
                }
                inMemoryGpxBytes = loaded
                gpxCacheKey = withContext(Dispatchers.Default) {
                    SnapCache.gpxBytesToCacheKeySha256Hex(loaded)
                }
                gpxData = withContext(Dispatchers.Default) {
                    parseGpx(ByteArrayInputStream(loaded))
                }

                val key = gpxCacheKey
                if (key != null) {
                    val cached = withContext(Dispatchers.IO) { SnapCache.load(appContext, key) }
                    if (cached != null) {
                        matchedTrackPoints = cached.snappedPoints
                        navigationInstructions = cached.instructions
                    }
                }
            } catch (_: Exception) {
                // If we can’t re-open (permissions), just leave it.
            }
        }
    }

    fun runMapMatchingIfPossible(mapboxToken: String, context: Context) {
        val appContext = context.applicationContext
        val data = gpxData ?: return
        if (mapboxToken.isBlank()) return
        if (data.trackPoints.size < 2) return

        // Requirement: do not call Mapbox at all for huge GPX
        if (data.trackPoints.size > 10_000) return

        // If we already have a result for this session, don’t rerun
        if (matchedTrackPoints != null) return

        viewModelScope.launch {
            try {
                // Try cache first (avoid duplicate Mapbox calls)
                val key = gpxCacheKey ?: inMemoryGpxBytes?.let { bytes ->
                    withContext(Dispatchers.Default) { SnapCache.gpxBytesToCacheKeySha256Hex(bytes) }
                }
                if (key != null) {
                    gpxCacheKey = key
                    val cached = withContext(Dispatchers.IO) { SnapCache.load(appContext, key) }
                    if (cached != null) {
                        matchedTrackPoints = cached.snappedPoints
                        navigationInstructions = cached.instructions
                        matchingProgress = null
                        return@launch
                    }
                }

                matchingProgress = 0 to ((data.trackPoints.size + 99) / 100)
                val result = MapboxMapMatching.snapDrivingTrace(
                    accessToken = mapboxToken.trim(),
                    trace = data.trackPoints,
                    onProgress = { cur, total ->
                        matchingProgress = cur to total
                    }
                )
                matchedTrackPoints = result.snappedPoints
                navigationInstructions = result.instructions
                matchingProgress = null

                // Save to cache for next time.
                val saveKey = gpxCacheKey
                if (saveKey != null) {
                    withContext(Dispatchers.IO) {
                        SnapCache.save(appContext, saveKey, result)
                    }
                }
            } catch (_: Exception) {
                matchedTrackPoints = null
                navigationInstructions = emptyList()
                matchingProgress = null
            }
        }
    }

    companion object {
        private const val KEY_GPX_URI = "gpxUri"
    }
}
