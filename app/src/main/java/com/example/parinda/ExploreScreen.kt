package com.example.parinda

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val MOTOVLOGGERS_URL = "https://arbaaz24.github.io/parinda/motovloggers.json"

@Composable
fun ExploreScreen(modifier: Modifier = Modifier) {
    val httpClient = remember { OkHttpClient() }

    var motovloggers by remember { mutableStateOf<List<Motovlogger>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var isCountryMenuOpen by rememberSaveable { mutableStateOf(false) }
    var selectedCountry by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(MOTOVLOGGERS_URL).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    parseMotovloggersJson(body)
                }
            }
            motovloggers = loaded

            // If the previously selected country no longer exists in the new dataset, clear it.
            val available = loaded.mapNotNull { it.country?.trim()?.takeIf(String::isNotBlank) }
            if (selectedCountry != null && available.none { it.equals(selectedCountry, ignoreCase = true) }) {
                selectedCountry = null
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load Explore list: ${e.message ?: "unknown error"}"
            motovloggers = emptyList()
        } finally {
            isLoading = false
        }
    }

    val countries = remember(motovloggers) {
        motovloggers
            .mapNotNull { it.country?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
    }

    val filtered = remember(motovloggers, selectedCountry) {
        val country = selectedCountry
        if (country.isNullOrBlank()) motovloggers
        else motovloggers.filter { it.country?.equals(country, ignoreCase = true) == true }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    enabled = !isLoading && errorMessage == null,
                    onClick = { isCountryMenuOpen = true }
                ) {
                    Text("Filter")
                }

                DropdownMenu(
                    expanded = isCountryMenuOpen,
                    onDismissRequest = { isCountryMenuOpen = false }
                ) {
                    if (isLoading) {
                        DropdownMenuItem(
                            text = { Text("Loading countries...") },
                            onClick = { },
                            enabled = false
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("All") },
                            onClick = {
                                selectedCountry = null
                                isCountryMenuOpen = false
                            }
                        )

                        if (countries.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No countries in data") },
                                onClick = { },
                                enabled = false
                            )
                        } else {
                            countries.forEach { country ->
                                DropdownMenuItem(
                                    text = { Text(country) },
                                    onClick = {
                                        selectedCountry = country
                                        isCountryMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                item {
                    Text(
                        text = "Loading...",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            items(filtered) { item ->
                Surface {
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            if (item.description.isNotBlank()) {
                                Text(item.description)
                            }
                        },
                        leadingContent = {
                            if (!item.avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = item.avatarUrl,
                                    contentDescription = "${item.name} channel image",
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

private data class Motovlogger(
    val name: String,
    val description: String,
    val country: String? = null,
    val channelUrl: String? = null,
    val avatarUrl: String? = null,
    // Preserve all fields from the remote JSON for future use.
    val rawJson: String
)

private fun parseMotovloggersJson(json: String): List<Motovlogger> {
    val array = JSONArray(json)
    val items = ArrayList<Motovlogger>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val item = obj.toMotovloggerOrNull() ?: continue
        items.add(item)
    }
    return items
}

private fun JSONObject.toMotovloggerOrNull(): Motovlogger? {
    // We only *use* these fields in the UI for now, but we keep the entire object in rawJson
    // so adding new UI fields later doesn't require changing the fetch layer.
    val name = optString("name", "").trim()
    if (name.isBlank()) return null

    return Motovlogger(
        name = name,
        description = optString("description", "").trim(),
        country = optString("country", "").trim().ifBlank { null },
        channelUrl = optString("channelUrl", "").trim().ifBlank { null },
        avatarUrl = optString("avatarUrl", "").trim().ifBlank { null },
        rawJson = toString()
    )
}
