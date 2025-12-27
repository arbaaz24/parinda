package com.example.parinda

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Represents a single point (lat/lon) with optional name and description (for waypoints/stops).
 */
data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val type: String? = null,
    val desc: String? = null
)

/**
 * Parsed GPX data: track points (the route line), start/end, and intermediate stops.
 */
data class GpxData(
    val trackPoints: List<GpxPoint>,
    val waypoints: List<GpxPoint>,
    val startPoint: GpxPoint?,
    val endPoint: GpxPoint?,
    val stops: List<GpxPoint>
)

private enum class WaypointRole { START, END, STOP }

private fun waypointRoleFromName(name: String?): WaypointRole? {
    val trimmed = name?.trim() ?: return null
    val lower = trimmed.lowercase()

    // Allow plain names for convenience
    if (lower == "start") return WaypointRole.START
    if (lower == "end" || lower == "finish") return WaypointRole.END

    val prefix = "_waypoint_"
    if (!lower.startsWith(prefix)) return null

    val remainder = lower.removePrefix(prefix).trim()
    val roleToken = remainder.split(Regex("[_\\s]+"), limit = 2)
        .firstOrNull()
        ?.trim()
        .orEmpty()

    return when (roleToken) {
        "start" -> WaypointRole.START
        "end", "finish" -> WaypointRole.END
        "stop" -> WaypointRole.STOP
        else -> WaypointRole.STOP
    }
}

/**
 * Parses a GPX file from an InputStream.
 * Extracts <trkpt> elements as track points and <wpt> elements as waypoints.
 * Identifies start, end, and stop points based on waypoint naming:
 * - Only waypoints whose <name> starts with "_waypoint_" are considered, plus plain "start"/"end".
 * - Preferred roles:
 *   - "_waypoint_start" => start
 *   - "_waypoint_end"   => end
 *   - "_waypoint_stop"  => stop
 * - Any other "_waypoint_*" is treated as a stop.
 * - Falls back to first/last track point if no explicit start/end waypoints.
 */
fun parseGpx(inputStream: InputStream): GpxData {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    val parser = factory.newPullParser()
    parser.setInput(inputStream, null)

    val trackPoints = mutableListOf<GpxPoint>()
    val waypoints = mutableListOf<GpxPoint>()

    var currentLat: Double? = null
    var currentLon: Double? = null
    var currentName: String? = null
    var currentType: String? = null
    var currentDesc: String? = null
    var inWpt = false
    var inTrkpt = false
    var inName = false
    var inType = false
    var inDesc = false

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        val tagName = parser.name

        when (eventType) {
            XmlPullParser.START_TAG -> {
                when (tagName) {
                    "wpt" -> {
                        inWpt = true
                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        currentName = null
                        currentType = null
                    }
                    "trkpt" -> {
                        inTrkpt = true
                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        currentName = null
                        currentType = null
                    }
                    "name" -> {
                        if (inWpt || inTrkpt) inName = true
                    }
                    "type" -> {
                        if (inWpt) inType = true
                    }
                    "desc" -> {
                        if (inWpt) inDesc = true
                    }
                }
            }
            XmlPullParser.TEXT -> {
                if (inName) {
                    currentName = parser.text?.trim()
                } else if (inType) {
                    currentType = parser.text?.trim()
                } else if (inDesc) {
                    currentDesc = parser.text?.trim()
                }
            }
            XmlPullParser.END_TAG -> {
                when (tagName) {
                    "wpt" -> {
                        if (currentLat != null && currentLon != null) {
                            // Only consider "_waypoint_*" plus plain "start"/"end".
                            // All other <wpt> entries are ignored.
                            if (waypointRoleFromName(currentName) != null) {
                                waypoints.add(GpxPoint(currentLat, currentLon, currentName, currentType, currentDesc))
                            }
                        }
                        inWpt = false
                        currentLat = null
                        currentLon = null
                        currentName = null
                        currentType = null
                        currentDesc = null
                    }
                    "trkpt" -> {
                        if (currentLat != null && currentLon != null) {
                            trackPoints.add(GpxPoint(currentLat, currentLon, currentName, currentType))
                        }
                        inTrkpt = false
                        currentLat = null
                        currentLon = null
                        currentName = null
                        currentType = null
                    }
                    "name" -> {
                        inName = false
                    }
                    "type" -> {
                        inType = false
                    }
                    "desc" -> {
                        inDesc = false
                    }
                }
            }
        }
        eventType = parser.next()
    }

    // Identify start, end, and stops from waypoints
    var startPoint: GpxPoint? = null
    var endPoint: GpxPoint? = null
    val stops = mutableListOf<GpxPoint>()

    for (wpt in waypoints) {
        when (waypointRoleFromName(wpt.name) ?: WaypointRole.STOP) {
            WaypointRole.START -> if (startPoint == null) startPoint = wpt
            WaypointRole.END -> if (endPoint == null) endPoint = wpt
            WaypointRole.STOP -> stops.add(wpt)
        }
    }

    // Fallback: use first and last track points if no explicit start/end waypoints
    if (startPoint == null && trackPoints.isNotEmpty()) {
        startPoint = trackPoints.first()
    }
    if (endPoint == null && trackPoints.isNotEmpty()) {
        endPoint = trackPoints.last()
    }

    return GpxData(
        trackPoints = trackPoints,
        waypoints = waypoints,
        startPoint = startPoint,
        endPoint = endPoint,
        stops = stops
    )
}
