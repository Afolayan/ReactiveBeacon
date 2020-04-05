package com.github.pwittchen.reactivebeacons.kotlinapp

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.pwittchen.reactivebeacons.library.rx2.Beacon
import com.github.pwittchen.reactivebeacons.library.rx2.ReactiveBeacons
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices.getFusedLocationProviderClient
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.annotations.NonNull
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.collections.HashSet


const val DB_BEACONS = "beacons"
const val DB_LOCATIONS = "test_locations"

class MainActivity : AppCompatActivity() {

    private var reactiveBeacons: ReactiveBeacons? = null
    private var subscription: Disposable? = null
    private var beacons: MutableMap<String, Beacon> = HashMap()
    private lateinit var locationCallback: LocationCallback
    private lateinit var firebaseFirestore: FirebaseFirestore
    private lateinit var firebaseBeaconListId: Set<String>
    private lateinit var mLocationRequest: LocationRequest
    private val UPDATE_INTERVAL: Long = 10 * 1000  /* 10 secs */
    private val FASTEST_INTERVAL: Long = 2000 /* 2 sec */

    private val geocoder = Geocoder(this)
    private var addressList = listOf<Address>()

    companion object {
        private val IS_AT_LEAST_ANDROID_M = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        private val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION = 1000
        private const val BEACON = "MAC: %s, RSSI: %d\ndistance: %.2fm, proximity: %s\n%s"
        private const val BLE_NOT_SUPPORTED = "BLE is not supported on this device"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        firebaseBeaconListId = HashSet()

        FirebaseApp.initializeApp(this)
        firebaseFirestore = FirebaseFirestore.getInstance()

        setSupportActionBar(toolbar)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                Log.e(TAG, "locationResult: $locationResult")
                locationResult ?: return

                processLocationInfo(locationResult.lastLocation)
            }
        }
    }

    private fun processLocationInfo(lastLocation: Location) {
        Log.e(TAG, "lastLocation: $lastLocation")
        Thread(
                Runnable {
                    try{
                        addressList =
                                geocoder.getFromLocation(lastLocation.latitude, lastLocation.longitude, 1)
                        val address = addressList[0]
                        val addressLine = address.getAddressLine(0)
                        val city = address.locality
                        val state = address.adminArea
                        val zip = address.postalCode
                        val country = address.countryName

                        runOnUiThread {
                            val addressString = "$addressLine, $city, $state, $country"
                            userLocationTextView.visibility = GONE
                            userLocationTextView.text = addressString

                            saveLocationToDb(
                                    FirebaseLocation(addressString,
                                            lastLocation.latitude,
                                            lastLocation.longitude,
                                            Date()
                                    )
                            )
                        }
                    } catch (ex: java.lang.Exception){
                        Log.e(TAG, "exception thrown: ", ex)
                    }


                }
        ).start()

    }

    override fun onResume() {
        super.onResume()
        reactiveBeacons = ReactiveBeacons(this)

        if (!canObserveBeacons()) {
            return
        }

        startSubscription()
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission") // permissions are requested in onResume()
    private fun startSubscription() {
        if (reactiveBeacons != null) {
            subscription = (reactiveBeacons as ReactiveBeacons).observe()
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { beacon -> beacons.put(beacon.device.address, beacon); refreshBeacons() }
        }
    }

    private fun getLocation(lastLocation: Location): Observable<Address> {
        return Observable.create { emitter ->
            try {
                addressList =
                        geocoder.getFromLocation(lastLocation.latitude, lastLocation.longitude, 1)

                emitter.onNext(addressList[0])
                emitter.onComplete()
            }  catch (e: Exception) {
                emitter.onError(e)
            }
        }
    }

    private fun processAddressInfo(address: Address, location: Location) {
        Log.e(TAG, "address: $address")
        val addressLine = address.getAddressLine(0)
        val city = address.locality
        val state = address.adminArea
        val zip = address.postalCode
        val country = address.countryName

        val addressString = "$addressLine, $city ($zip), $state, $country"
        userLocationTextView.visibility = GONE
        userLocationTextView.text = addressString

        saveLocationToDb(
                FirebaseLocation(addressString,
                        location.latitude,
                        location.longitude,
                        Date()
                )
        )
    }

    private fun startLocationUpdates() {
        // Create the location request to start receiving updates
        mLocationRequest = LocationRequest()
        mLocationRequest.apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = UPDATE_INTERVAL
            fastestInterval = FASTEST_INTERVAL
        }
        val fusedLocationClient = getFusedLocationProviderClient(this)
        fusedLocationClient.requestLocationUpdates(mLocationRequest,
                locationCallback,
                Looper.getMainLooper())

        fusedLocationClient.lastLocation
                .addOnSuccessListener { location : Location? ->
                    location?.apply {
                        getLocation(this)
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(object : Observer<Address> {
                                    override fun onComplete() {
                                        Log.e("MainActivity", "location subscriber is complete")
                                    }

                                    override fun onSubscribe(d: Disposable) {
                                        Log.e("MainActivity", "location subscriber is on")
                                    }

                                    override fun onNext(address: Address) {
                                        processAddressInfo(address, this@apply)
                                    }

                                    override fun onError(e: Throwable) {
                                        Log.e("MainActivity", "error is: ", e)
                                    }

                                })
                    }
                }
    }

    private fun canObserveBeacons(): Boolean {

        if (reactiveBeacons != null) {

            if (!(reactiveBeacons as ReactiveBeacons).isBleSupported) {
                Toast.makeText(this, BLE_NOT_SUPPORTED, Toast.LENGTH_SHORT)
                        .show()
                return false
            }

            if (!(reactiveBeacons as ReactiveBeacons).isBluetoothEnabled) {
                (reactiveBeacons as ReactiveBeacons).requestBluetoothAccess(this)
                return false
            } else if (!(reactiveBeacons as ReactiveBeacons).isLocationEnabled(this)) {
                (reactiveBeacons as ReactiveBeacons).requestLocationAccess(this)
                return false
            } else if (!isFineOrCoarseLocationPermissionGranted() && IS_AT_LEAST_ANDROID_M) {
                requestCoarseLocationPermission()
                return false
            }

            return true
        }

        return false
    }

    private fun refreshBeacons() {
        val list = beacons.values.map {
            BEACON.format(it.device.address, it.rssi, it.distance, it.proximity, it.device.name)
        }

        val firebaseReadyList = beacons.values.map {
            FirebaseBeacon.fromBeacon(it)
        }
        loaderLayout.visibility = GONE
        beaconsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = BeaconRecyclerAdapter(
                    beacons.values.toList()
            ) {
                val message = "Mac ID ${it.macAddress} pushed to db."
                saveToDb(it, message)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        safelyUnsubscribe(subscription)
    }

    private fun safelyUnsubscribe(subscription: Disposable?) {
        if (subscription != null && !subscription.isDisposed) {
            subscription.dispose()
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, @NonNull permissions: Array<String>,
            @NonNull grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val isCoarseLocation = requestCode == PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION
        val permissionGranted = grantResults[0] == PERMISSION_GRANTED

        if (isCoarseLocation && permissionGranted && subscription == null) {
            startSubscription()
        }
    }

    private fun requestCoarseLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                    arrayOf<String>(ACCESS_COARSE_LOCATION),
                    PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun isFineOrCoarseLocationPermissionGranted(): Boolean {
        val isAndroidMOrHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val isFineLocationPermissionGranted = isGranted(ACCESS_FINE_LOCATION)
        val isCoarseLocationPermissionGranted = isGranted(ACCESS_COARSE_LOCATION)

        return isAndroidMOrHigher && (isFineLocationPermissionGranted || isCoarseLocationPermissionGranted)
    }

    private fun isGranted(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(this, permission) == PERMISSION_GRANTED
    }

    private fun saveToDb(beacon: FirebaseBeacon, message: String?) {

        beacon.timestamp = Date()
        firebaseFirestore.collection(DB_BEACONS)
                .add(beacon)
                .addOnSuccessListener { documentReference ->
                    firebaseBeaconListId = firebaseBeaconListId.plusElement(beacon.macAddress!!)
                    Log.d(TAG, "DocumentSnapshot added with ID: ${documentReference.id}")
                    message?.let {
                        Toast.makeText(applicationContext,
                                        message,
                                        Toast.LENGTH_SHORT)
                                .show()
                    }

                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Error adding document", e)
                    Toast
                            .makeText(applicationContext,
                                    "Could not save this beacon at this time. Please try again later.",
                                    Toast.LENGTH_SHORT)
                            .show()
                }
    }

    private fun saveLocationToDb(firebaseLocation: FirebaseLocation){
        firebaseFirestore.collection(DB_LOCATIONS)
                .add(firebaseLocation)
                .addOnSuccessListener { documentReference ->
                    Log.d(TAG, "DocumentSnapshot added with ID: ${documentReference.id}")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Error adding document", e)
                }
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menuSaveAll -> {
                beacons.values.forEach { beacon ->
                    val firebaseBeacon = FirebaseBeacon.fromBeacon(beacon)
                    saveToDb(firebaseBeacon, null)
                }
                val message = "All detected BLE scan pushed to db."
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
