package com.example.parinda

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SnapCache {
    private const val CACHE_DIR = "snapped_routes"
    private const val CACHE_VERSION = 1

    fun gpxBytesToCacheKeySha256Hex(gpxBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(gpxBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun cacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cacheFile(context: Context, key: String): File {
        return File(cacheDir(context), "$key.json.gz")
    }

    fun load(context: Context, key: String): MatchedRoute? {
        val file = cacheFile(context, key)
        if (!file.exists()) return null

        return try {
            val jsonText = GZIPInputStream(FileInputStream(file)).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            if (root.optInt("v", -1) != CACHE_VERSION) return null

            val snapped = root.optJSONArray("snapped") ?: return null
            val snappedPoints = ArrayList<GpxPoint>(snapped.length())
            for (i in 0 until snapped.length()) {
                val pair = snapped.getJSONArray(i)
                val lat = pair.getDouble(0)
                val lon = pair.getDouble(1)
                snappedPoints.add(GpxPoint(lat = lat, lon = lon))
            }

            val instructionsArr = root.optJSONArray("instructions") ?: JSONArray()
            val instructions = ArrayList<NavigationInstruction>(instructionsArr.length())
            for (i in 0 until instructionsArr.length()) {
                val obj = instructionsArr.getJSONObject(i)
                val instruction = obj.optString("instruction", "")
                val maneuverType = obj.optString("maneuverType", "")
                val modifier = obj.optString("modifier", null)
                val distanceMeters = obj.optDouble("distanceMeters", 0.0)
                val durationSeconds = obj.optDouble("durationSeconds", 0.0)
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (instruction.isBlank() || lat.isNaN() || lon.isNaN()) continue

                instructions.add(
                    NavigationInstruction(
                        instruction = instruction,
                        maneuverType = maneuverType,
                        modifier = modifier,
                        distanceMeters = distanceMeters,
                        durationSeconds = durationSeconds,
                        location = GpxPoint(lat = lat, lon = lon)
                    )
                )
            }

            MatchedRoute(snappedPoints = snappedPoints, instructions = instructions)
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, key: String, route: MatchedRoute) {
        val file = cacheFile(context, key)
        val tmp = File(file.parentFile, file.name + ".tmp")

        val root = JSONObject().apply {
            put("v", CACHE_VERSION)
            put("profile", "driving")
            put("steps", true)

            val snapped = JSONArray()
            for (p in route.snappedPoints) {
                snapped.put(JSONArray().put(p.lat).put(p.lon))
            }
            put("snapped", snapped)

            val instructionsArr = JSONArray()
            for (instr in route.instructions) {
                val obj = JSONObject()
                obj.put("instruction", instr.instruction)
                obj.put("maneuverType", instr.maneuverType)
                obj.put("modifier", instr.modifier)
                obj.put("distanceMeters", instr.distanceMeters)
                obj.put("durationSeconds", instr.durationSeconds)
                obj.put("lat", instr.location.lat)
                obj.put("lon", instr.location.lon)
                instructionsArr.put(obj)
            }
            put("instructions", instructionsArr)
        }

        // Atomic-ish write: write temp then rename.
        GZIPOutputStream(FileOutputStream(tmp)).bufferedWriter().use { it.write(root.toString()) }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }
}
