package com.n8rtz.winetracker.image

import android.content.Context
import java.io.File

/** Prefer the approved local file; callers may pass a URL only for legacy records with no provenance row. */
fun resolveWineImageModel(
    context: Context,
    relativePath: String?,
    legacyImageUrl: String?
): Any? {
    val localFile = WineImageFileStore.localFile(context, relativePath)
    if (localFile?.isFile == true) return localFile
    return legacyImageUrl?.takeIf(String::isNotBlank)
}
