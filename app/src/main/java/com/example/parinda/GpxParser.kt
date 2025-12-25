package com.example.parinda

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Represents a single point (lat/lon) with optional name (for waypoints/stops).
 */
data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val type: String? = null
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

/**
 * Parses a GPX file from an InputStream.
 * Extracts <trkpt> elements as track points and <wpt> elements as waypoints.
 * Identifies start, end, and stop points based on:
 * - Waypoint names containing "start", "end", "stop", "finish", etc.
 * - Waypoint <type> tags
 * - Falls back to first/last track point if no explicit start/end waypoints
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
    var inWpt = false
    var inTrkpt = false
    var inName = false
    var inType = false

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
                }
            }
            XmlPullParser.TEXT -> {
                if (inName) {
                    currentName = parser.text?.trim()
                } else if (inType) {
                    currentType = parser.text?.trim()
                }
            }
            XmlPullParser.END_TAG -> {
                when (tagName) {
                    "wpt" -> {
                        if (currentLat != null && currentLon != null) {
                            waypoints.add(GpxPoint(currentLat, currentLon, currentName, currentType))
                        }
                        inWpt = false
                        currentLat = null
                        currentLon = null
                        currentName = null
                        currentType = null
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
        val nameLower = wpt.name?.lowercase() ?: ""
        val typeLower = wpt.type?.lowercase() ?: ""
        
        when {
            // Check for start indicators
            nameLower.contains("start") || typeLower.contains("start") -> {
                if (startPoint == null) startPoint = wpt
            }
            // Check for end/finish indicators
            nameLower.contains("end") || nameLower.contains("finish") || 
            typeLower.contains("end") || typeLower.contains("finish") -> {
                if (endPoint == null) endPoint = wpt
            }
            // Check for stop indicators
            nameLower.contains("stop") || nameLower.contains("waypoint") || 
            typeLower.contains("stop") || typeLower.contains("waypoint") -> {
                stops.add(wpt)
            }
            // Default: treat unnamed/untyped waypoints as stops
            else -> {
                stops.add(wpt)
            }
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
