package com.freelapp.libs.locationservice

import android.content.*
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.freelapp.libs.locationservice.LocationService.Companion.logd
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.*
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.actor
import com.google.android.gms.common.api.ResolvableApiException
import kotlinx.coroutines.channels.SendChannel
import java.lang.ClassCastException

/**
 * Service connection vars and binding code common to all classes is in this abstract class.
 */
abstract class LocationActivity : AppCompatActivity(), ILocationListener {

    companion object {
        const val HAS_LOCATION_PERMISSION_CODE = 11666
        const val REQUEST_CHECK_SETTINGS = 11667
        val LOCATION_PERMISSION = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        var askForPermissionUntilGiven: Boolean = false
        var requestPermissionRationale: Int = R.string.need_location_permission
    }

    private sealed class LocationServiceMsg {
        class AddLocationListener(val listener: ILocationListener) : LocationServiceMsg()
        class RemoveLocationListener(val listener: ILocationListener) : LocationServiceMsg()
        object StartRequestingLocationUpdates : LocationServiceMsg()
        object StopRequestingLocationUpdates : LocationServiceMsg()
        object BroadcastLocation : LocationServiceMsg()
    }

    @ObsoleteCoroutinesApi
    private lateinit var locationServiceActor: SendChannel<LocationServiceMsg>

    var locationService: LocationService? = null
        private set

    private var bound: Boolean = false

    private val locationServiceConn: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            locationService = (service as LocationService.LocalBinder).service.apply {
                addLocationListener(this@LocationActivity)
            }
            bound = true

            logd("LocationActivity", "LocationService bound.")

            onLocationServiceConnected() // optionally overridden by children activities
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            locationService = null
            bound = false

            logd("LocationActivity", "LocationService disconnected (unbounded).")

            onLocationServiceDisconnected()
        }
    }

    @ObsoleteCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationServiceActor = lifecycleScope.actor(Dispatchers.Main) {
            for (msg in channel) {
                // avoids processing messages while service not bound, but instead of ignoring command, wait for binding.
                // that is why actors are used here.
                while (!bound) delay(200)

                when (msg) {
                    is LocationServiceMsg.AddLocationListener -> locationService?.addLocationListener(msg.listener)
                    is LocationServiceMsg.RemoveLocationListener -> locationService?.removeLocationListener(msg.listener)
                    LocationServiceMsg.StartRequestingLocationUpdates -> locationService?.startRequestingLocationUpdates()
                    LocationServiceMsg.StopRequestingLocationUpdates -> locationService?.stopRequestingLocationUpdates()
                    LocationServiceMsg.BroadcastLocation -> locationService?.broadcastLocation()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CHECK_SETTINGS -> when (resultCode) {
                RESULT_OK -> {
                    logd("Location is enabled in Settings")
                    onLocationSettingsOn()
                    askForPermissionIfNeededAndBindIfNotAlready()
                }
                RESULT_CANCELED -> {
                    logd("Location is NOT enabled in Settings")
                    showSnackbar(R.string.need_location_settings)
                    checkSettings()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkSettings()
    }

    override fun onPause() {
        super.onPause()

        logd("onPause")

        if (bound) {
            logd("onPause: Unbinding service...")

            locationService?.removeLocationListener(this)
            unbindService(locationServiceConn)
            bound = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            HAS_LOCATION_PERMISSION_CODE -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.firstOrNull() == PERMISSION_GRANTED) {
                    permissionGrantedCallCallbackAndBind()
                } else if (shouldShowRationale()) {
                    logd("Permission failed")
                    showSnackbar(requestPermissionRationale)

                    if (!hasPermission() && askForPermissionUntilGiven)
                        askForPermission()
                }
            }
        }
    }

    /**
     * Private methods
     */
    private fun showSnackbar(res: Int) {
        Snackbar
            .make(
                findViewById(android.R.id.content),
                getString(res),
                Snackbar.LENGTH_LONG
            )
            .show()
    }

    private fun bind() = bindService(
        Intent(this, LocationService::class.java),
        locationServiceConn,
        Context.BIND_AUTO_CREATE
    ).also { logd("LocationService - bindService() called.") }

    private fun checkSettings() {
        logd("Checking settings")

        val builder = LocationSettingsRequest.Builder().addLocationRequest(LocationRequest.create())
        val task = LocationServices.getSettingsClient(this).checkLocationSettings(builder.build())

        task.addOnCompleteListener {
            try {
                task.getResult(ApiException::class.java)
                // All location settings are satisfied. The client can initialize location
                logd("Checking settings - Satisfied.")
                onLocationSettingsOn()
                askForPermissionIfNeededAndBindIfNotAlready()
            } catch (e: ApiException) {
                logd("Checking settings - Not satisfied. Status code: ${e.statusCode}: ${e.localizedMessage}.")
                when (e.statusCode) {
                    LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                        logd("Checking settings - Resolution required")

                        try {
                            // Cast to a resolvable exception.
                            val resolvable = e as ResolvableApiException
                            // Show the dialog by calling startResolutionForResult(),
                            // and check the result in onActivityResult().

                            logd("Checking settings - Resolution is possible. Requesting...")

                            resolvable.startResolutionForResult(
                                this,
                                REQUEST_CHECK_SETTINGS
                            )

                        } catch (_: IntentSender.SendIntentException) {
                            // Ignore the error.
                            logd("Checking settings - SendIntentException")
                        } catch (_: ClassCastException) {
                            logd("Checking settings - ClassCastException")
                            // Ignore, should be an impossible error.
                        }
                    }
                }
            }
        }
    }

    private fun askForPermissionIfNeededAndBindIfNotAlready() {
        if (!bound) {
            if (!hasPermission())
                askForPermission()
            else
                permissionGrantedCallCallbackAndBind()
        }
    }

    private fun permissionGrantedCallCallbackAndBind() {
        logd("Permission granted. Binding service.")
        onLocationPermissionGranted()
        bind()
    }

    private fun askForPermission() {
        logd("asking for permission")
        ActivityCompat.requestPermissions(this, LOCATION_PERMISSION, HAS_LOCATION_PERMISSION_CODE)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, LOCATION_PERMISSION.first()) == PERMISSION_GRANTED

    private fun shouldShowRationale() =
        ActivityCompat.shouldShowRequestPermissionRationale(this, LOCATION_PERMISSION.first())

    /**
     * Public methods
     */
    /* update whoever registers about location changes */
    @ObsoleteCoroutinesApi
    fun addLocationListener(listener: ILocationListener) = lifecycleScope.launch {
        locationServiceActor.send(LocationServiceMsg.AddLocationListener(listener))
    }

    @ObsoleteCoroutinesApi
    fun removeLocationListener(listener: ILocationListener) = lifecycleScope.launch {
        locationServiceActor.send(LocationServiceMsg.RemoveLocationListener(listener))
    }

    @ObsoleteCoroutinesApi
    fun startRequestingLocationUpdates() = lifecycleScope.launch {
        locationServiceActor.send(LocationServiceMsg.StartRequestingLocationUpdates)
    }

    @ObsoleteCoroutinesApi
    fun stopRequestingLocationUpdates() = lifecycleScope.launch {
        locationServiceActor.send(LocationServiceMsg.StopRequestingLocationUpdates)
    }

    @ObsoleteCoroutinesApi
    fun broadcastLocation() = lifecycleScope.launch {
        locationServiceActor.send(LocationServiceMsg.BroadcastLocation)
    }

    // override this !
    protected open fun onLocationServiceConnected() = Unit
    protected open fun onLocationServiceDisconnected() = Unit
    protected open fun onLocationPermissionGranted() = Unit
    protected open fun onLocationSettingsOn() = Unit
}