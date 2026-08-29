package com.prestondihle.healthtracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.ui.navigation.TrackerNavHost
import com.prestondihle.healthtracker.ui.theme.HealthTrackerTheme
import com.prestondihle.healthtracker.ui.theme.resolvedDark
import com.prestondihle.healthtracker.work.CaffeineLastCallWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as TrackerApp).container

        // Both are cheap and idempotent: the channel has to exist before
        // anything can be delivered to it, and the work is uniquely named, so
        // registering it on each launch replaces rather than accumulates.
        //
        // Here rather than in Application.onCreate, which is where they started.
        // WorkManager initialises through an androidx.startup provider that does
        // not run under Robolectric, so scheduling from the Application made
        // every test that touches it throw -- and an Application that cannot be
        // constructed in a test is a bad trade for scheduling a few milliseconds
        // earlier. Nothing here needs to happen before a window exists.
        CaffeineLastCallWorker.createChannel(this)
        CaffeineLastCallWorker.schedule(this)

        // Asked once, at launch, and never insisted on. The only thing behind it
        // is the caffeine last-call warning, which the worker checks for before
        // posting -- so a refusal means that warning never appears and nothing
        // else changes. Below Android 13 the permission does not exist and
        // notifications work without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    .launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            // Read here rather than inside the theme so there is exactly one
            // subscription to it, and collected with a null initial value on
            // purpose: the row arrives a frame or two after the window, and
            // `resolvedDark` reads that null as "follow the phone" -- the
            // behaviour that shipped before this setting existed. Guessing a
            // scheme instead would paint one and repaint in the other on every
            // launch, which is a visible flash on the reader whose choice
            // differs from their phone.
            val settings by
                appContainer.trackerRepository
                    .getUserSettings()
                    .collectAsStateWithLifecycle(initialValue = null)

            HealthTrackerTheme(darkTheme = settings?.themeMode.resolvedDark()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TrackerNavHost(appContainer)
                }
            }
        }
    }
}

