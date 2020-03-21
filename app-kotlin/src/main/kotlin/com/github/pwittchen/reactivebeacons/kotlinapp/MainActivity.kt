package com.github.pwittchen.reactivebeacons.kotlinapp

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.location.Address
import android.location.Geocoder
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
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.annotations.NonNull
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.collections.HashSet


const val DB_BEACONS = "beacons"

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

    private val geocoder = Geocoder(this, Locale.getDefault())
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
                locationResult ?: return

                processLocationInfo(locationResult)
            }
        }
    }

    private fun processLocationInfo(locationResult: LocationResult) {
        val lastLocation = locationResult.lastLocation
        Thread(
                Runnable {
                    addressList =
                            geocoder.getFromLocation(lastLocation.latitude, lastLocation.longitude, 1)
                    val address = addressList[0].getAddressLine(0)
                    val city = addressList[0].locality
                    val state = addressList[0].adminArea
                    val zip = addressList[0].postalCode
                    val country = addressList[0].countryName

                    val addressString = "$address, $city ($zip), $state, $country"
                    userLocationTextView.visibility = View.VISIBLE
                    userLocationTextView.text = addressString
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
        Log.e("MainA: ", "firebaseReadyList is ${firebaseReadyList.size}")
        loaderLayout.visibility = GONE
        beaconsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = BeaconRecyclerAdapter(
                    beacons.values.toList()
            ) {
                saveToDb(it)
            }
        }
        //beacons.values.forEach(this::saveToDb)

        //lv_beacons.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list)
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

    private fun saveToDb(beacon: FirebaseBeacon) {

        beacon.date = Date()
//        if (firebaseBeaconListId.contains(beacon.macAddress)){
//
//            //firebaseFirestore.collection(DB_BEACONS).do("macAddress", beacon.macAddress).
//            return
//        }
        // Add a new document with a generated ID
        firebaseFirestore.collection(DB_BEACONS)
                .add(beacon)
                .addOnSuccessListener { documentReference ->
                    firebaseBeaconListId = firebaseBeaconListId.plusElement(beacon.macAddress!!)
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
                    saveToDb(firebaseBeacon)
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
