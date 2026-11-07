package com.yourapp.wine.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** No coordinates: camera permission and the system photo picker are separate, explicit user actions. */
@Composable
fun LabelScanActions(busy: Boolean, progressMessage: String?, onCamera: () -> Unit, onChoosePhoto: () -> Unit,
    progress: Float? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCamera, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Camera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan label with camera")
        }
        OutlinedButton(onClick = onChoosePhoto, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose label photo")
        }
        Text("Camera and selected photos are scanned on this device. No photo upload.",
            style = MaterialTheme.typography.bodySmall)
        progressMessage?.let {
            val animated by animateFloatAsState(targetValue = progress ?: 0f, label = "labelScanProgress")
            if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
