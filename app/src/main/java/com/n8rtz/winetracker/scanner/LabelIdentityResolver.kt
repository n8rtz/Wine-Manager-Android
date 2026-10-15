package com.n8rtz.winetracker.scanner

import android.util.Log
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a decoded barcode into a wine identity.
 *
 * Every lookup is remembered, so scanning the same bottle again is instant and keeps working with
 * no connection. A lookup failure is never fatal: the scan falls back to what the label photo read.
 */
internal class LabelIdentityResolver(
    private val api: OpenFoodFactsApi,
    private val cache: BarcodeIdentityCache
) {
    suspend fun resolve(barcode: String): WineBarcodeIdentity? {
        val normalized = Gtin.normalize(barcode) ?: return null
        cache.get(normalized)?.let { cached ->
            Log.i(TAG, "Barcode $normalized resolved from the local cache")
            return cached
        }
        val identity = withContext(Dispatchers.IO) {
            try {
                OffWineProductMapper.map(normalized, api.product(normalized))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Barcode $normalized lookup failed; using the label photo only", error)
                null
            }
        }
        if (identity != null) {
            cache.put(identity)
        } else {
            Log.i(TAG, "Barcode $normalized is not in the product registry")
        }
        return identity
    }

    private companion object {
        const val TAG = "LabelIdentityResolver"
    }
}
