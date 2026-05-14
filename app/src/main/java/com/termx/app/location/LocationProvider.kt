package com.termx.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * GPS/Location provider for TermX API.
 * Provides location data similar to termux-location.
 *
 * Usage: termx-location [-p provider] [-r request]
 *   -p gps|network|passive  (default: gps)
 *   -r once|updates         (default: once)
 */
object LocationProvider {

    private const val TAG = "LocationProvider"

    private var locationCallback: ((LocationResult) -> Unit)? = null

    data class LocationResult(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
        val speed: Float,
        val bearing: Float,
        val provider: String,
        val timestamp: Long,
        val address: String?
    ) {
        fun toFormattedString(): String {
            return buildString {
                appendLine("=== Location ===")
                appendLine("Latitude:  $latitude")
                appendLine("Longitude: $longitude")
                appendLine("Altitude:  ${"%.1f".format(altitude)}m")
                appendLine("Accuracy:  ${"%.1f".format(accuracy)}m")
                appendLine("Speed:     ${"%.1f".format(speed)}m/s")
                appendLine("Bearing:   ${"%.1f".format(bearing)}°")
                appendLine("Provider:  $provider")
                appendLine("Time:      ${java.text.DateFormat.getDateTimeInstance().format(timestamp)}")
                if (address != null) {
                    appendLine("Address:   $address")
                }
            }
        }
    }

    /**
     * Request a single location update.
     */
    @SuppressLint("MissingPermission")
    fun requestLocation(
        context: Context,
        provider: String = LocationManager.GPS_PROVIDER,
        timeoutMs: Long = 30000,
        callback: (LocationResult) -> Unit
    ) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Check if provider is available
        if (!locationManager.isProviderEnabled(provider)) {
            // Try network provider as fallback
            val fallbackProvider = LocationManager.NETWORK_PROVIDER
            if (locationManager.isProviderEnabled(fallbackProvider)) {
                requestLocationInternal(context, locationManager, fallbackProvider, timeoutMs, callback)
            } else {
                callback(
                    LocationResult(
                        latitude = 0.0, longitude = 0.0, altitude = 0.0,
                        accuracy = 0f, speed = 0f, bearing = 0f,
                        provider = "unavailable", timestamp = System.currentTimeMillis(),
                        address = null
                    )
                )
            }
            return
        }

        requestLocationInternal(context, locationManager, provider, timeoutMs, callback)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationInternal(
        context: Context,
        locationManager: LocationManager,
        provider: String,
        timeoutMs: Long,
        callback: (LocationResult) -> Unit
    ) {
        // Try to get last known location first
        val lastLocation = locationManager.getLastKnownLocation(provider)
        if (lastLocation != null && (System.currentTimeMillis() - lastLocation.time) < 60000) {
            // Recent location available
            deliverResult(context, lastLocation, callback)
            return
        }

        // Request fresh location
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                deliverResult(context, location, callback)
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {
                locationManager.removeUpdates(this)
            }
        }

        locationManager.requestLocationUpdates(
            provider, 0L, 0f, listener, Looper.getMainLooper()
        )

        // Timeout
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            locationManager.removeUpdates(listener)
            // Return last known if available
            val last = locationManager.getLastKnownLocation(provider)
            if (last != null) {
                deliverResult(context, last, callback)
            }
        }, timeoutMs)
    }

    private fun deliverResult(
        context: Context,
        location: Location,
        callback: (LocationResult) -> Unit
    ) {
        var address: String? = null
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses: List<Address>? = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                address = addresses[0].getAddressLine(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder failed", e)
        }

        callback(
            LocationResult(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy,
                speed = location.speed,
                bearing = location.bearing,
                provider = location.provider ?: "unknown",
                timestamp = location.time,
                address = address
            )
        )
    }
}
