package com.example.parinda

/**
 * A single navigation instruction (turn, continue, arrive, etc.)
 */
data class NavigationInstruction(
    val instruction: String,       // "Turn left onto Main Street"
    val maneuverType: String,      // "turn", "depart", "arrive", "continue", etc.
    val modifier: String?,         // "left", "right", "straight", etc.
    val distanceMeters: Double,    // Distance of this step
    val durationSeconds: Double,   // Estimated duration
    val location: GpxPoint         // Where this maneuver occurs
)

/**
 * Result of map matching: snapped route + navigation directions
 */
data class MatchedRoute(
    val snappedPoints: List<GpxPoint>,
    val instructions: List<NavigationInstruction>
)
