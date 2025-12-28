package com.example.parinda

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExploreDetailScreen(
    motovlogger: Motovlogger,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = motovlogger.name, style = MaterialTheme.typography.headlineSmall)

        if (motovlogger.country?.isNotBlank() == true) {
            Text(
                text = motovlogger.country,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (motovlogger.description.isNotBlank()) {
            Text(
                text = motovlogger.description,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
