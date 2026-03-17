9. ViewModel Integration

// ui/components/WineImageBox.kt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

@Composable
fun WineImageBox(wine: WineDefinition) {

    val fallbackColor = when (wine.type.lowercase()) {
        "red"       -> Color(0xFFFFF5F5)   // Subtle blush
        "white"     -> Color(0xFFFFFFF0)   // Subtle straw
        "rosé",
        "rose"      -> Color(0xFFFFF0F5)   // Subtle pink
        "sparkling" -> Color(0xFFF5F5F0)   // Subtle champagne
        else        -> Color(0xFFF8F9FA)   // Neutral off-white
    }

    Box(
        modifier = Modifier
            .size(60.dp, 80.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(fallbackColor)
    ) {
        if (wine.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(wine.imageUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "${wine.maker} ${wine.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}


0. Import Progress UI
   // ui/components/ImageImportProgress.kt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ImageImportProgress(viewModel: WineViewModel) {

    val state by viewModel.imageImportProgress.collectAsState()

    AnimatedVisibility(visible = state !is ImageImportState.Idle) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val current = state) {

                    is ImageImportState.InProgress -> {
                        Text(
                            text = "Finding bottle images...",
                            style = MaterialTheme.typography.titleSmall
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = {
                                current.completed.toFloat() / current.total
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF722F37)  // Bordeaux Red
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${current.completed} / ${current.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8E8E93)
                        )
                    }

                    is ImageImportState.Done -> {
                        Text(
                            text = "Done! Found images for " +
                                "${current.found} of ${current.total} wines.",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    else -> { /* Idle — hidden by AnimatedVisibility */ }
                }
            }
        }
    }
}

11. Room Entity & DAO Updates
    // data/entities/WineDefinition.kt

@Entity
data class WineDefinition(
@PrimaryKey(autoGenerate = true) val id: Long = 0,
val maker: String,
val name: String,
val type: String,         // "Red", "White", "Sparkling", etc.
val country: String,
val region: String,
val appellation: String,
val imageUrl: String? = null  // Cached image URL — null = not yet fetched
)


// data/dao/WineDao.kt (add these queries to your existing DAO)

@Dao
interface WineDao {

    @Query("UPDATE wine_definition SET imageUrl = :url WHERE id = :wineId")
    suspend fun updateImageUrl(wineId: Long, url: String)

    @Query("SELECT * FROM wine_definition WHERE imageUrl IS NULL")
    suspend fun getAllWithoutImages(): List<WineDefinition>

    @Query("SELECT imageUrl FROM wine_definition WHERE id = :wineId")
    suspend fun getImageUrl(wineId: Long): String?
}


12. Dependencies
    // build.gradle.kts (app level)

dependencies {
// Networking
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Room (you likely already have this)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}