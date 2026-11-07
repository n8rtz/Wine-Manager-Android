package com.yourapp.wine.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

/** No coordinates: camera permission and the system photo picker are separate, explicit user actions. */
@Composable
fun LabelScanActions(busy: Boolean, progressMessage: String?, onCamera: () -> Unit, onChoosePhoto: () -> Unit,
    progress: Float? = null, scanPhotoPath: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCamera, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Camera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan label")
        }
        OutlinedButton(onClick = onChoosePhoto, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose label")
        }
        Text("Camera and selected photos are scanned on this device. No photo upload.",
            style = MaterialTheme.typography.bodySmall)
        // The scanning photo, kept on screen after the scan so the bottle stays visible with its form.
        scanPhotoPath?.let {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(it))
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
                contentDescription = "Scanned wine label",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        progressMessage?.let {
            val animated by animateFloatAsState(targetValue = progress ?: 0f, label = "labelScanProgress")
            if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
