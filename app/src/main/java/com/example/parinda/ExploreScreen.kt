package com.example.parinda

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import android.content.Intent
import android.net.Uri

@Composable
fun ExploreScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val motovloggers = rememberMotovloggers()

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(motovloggers) { item ->
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
                        .clickable(enabled = !item.channelUrl.isNullOrBlank()) {
                            val url = item.channelUrl ?: return@clickable
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            )
                        }
                        .padding(horizontal = 8.dp)
                )
            }
        }
    }
}

private data class Motovlogger(
    val name: String,
    val description: String,
    val channelUrl: String? = null,
    val avatarUrl: String? = null
)

@Composable
private fun rememberMotovloggers(): List<Motovlogger> {
    // NOTE: “Exact channel picture” requires either:
    // - YouTube Data API (fetch channel snippet thumbnails), or
    // - You manually providing the avatarUrl for each channel.
    // For now, these are placeholders; replace channelUrl/avatarUrl with the channels you want.
    return listOf(
        Motovlogger(
            name = "Motovlogger 1",
            description = "Short description goes here.",
            channelUrl = null,
            avatarUrl = null
        ),
        Motovlogger(
            name = "Motovlogger 2",
            description = "Short description goes here.",
            channelUrl = null,
            avatarUrl = null
        ),
        Motovlogger(
            name = "Motovlogger 3",
            description = "Short description goes here.",
            channelUrl = null,
            avatarUrl = null
        )
    )
}
