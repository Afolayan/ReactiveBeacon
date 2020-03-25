package com.github.pwittchen.reactivebeacons.kotlinapp

import com.github.pwittchen.reactivebeacons.library.rx2.Beacon
import java.util.*

data class FirebaseBeacon (
        val beaconName: String? = null,
        val macAddress: String? = null,
        val distance: Double = 0.0,
        val proximityDescription: String? = null,
        val rssi: Int = 0,
        var timestamp: Date? = null
) {
    companion object {
        @JvmStatic
        fun fromBeacon(beacon: Beacon) : FirebaseBeacon {
            return FirebaseBeacon(
                    beaconName = beacon.device.name,
                    macAddress = beacon.device.address,
                    distance = beacon.distance,
                    proximityDescription = beacon.proximity.description,
                    rssi = beacon.rssi
            )
        }
    }

}

data class FirebaseLocation (
        val address: String? = null,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val timestamp: Date? = null
)