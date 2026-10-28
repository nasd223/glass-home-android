package com.glasshome.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import com.glasshome.tv.web.PhotoService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var apps: Apps
    private lateinit var state: HomeState
    private lateinit var photos: PhotoLibrary
    private lateinit var service: PhotoService
    private var wakingKey = -1
    private var loading: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = (application as GlassHomeApp).service
        apps = Apps(this)
        state = HomeState(apps, service)
        state.quickSettings = QuickSettings.items(packageManager)
        photos = PhotoLibrary(this)
        val weather = if (Config.weather.enabled) WeatherSource(this, Config.weather) else null
        setContent {
            HomeScreen(state, photos, weather, ::launch)
        }
        // Photos, shortcuts and the password change from the phone at any time.
        lifecycleScope.launch {
            service.revision.collectLatest { refreshPhone() }
        }
    }

    private suspend fun refreshPhone() {
        val tv = withContext(Dispatchers.IO) { service.tvState() }
        if (tv == null) {
            state.phone = service.startError?.let { Phone(null, null, it) }
            return
        }
        photos.setUploads(tv.photos)
        state.shortcuts = withContext(Dispatchers.IO) { Shortcuts.tiles(tv.shortcuts, Shortcuts.youtubeApp(packageManager)) }
        state.phone = Phone(tv.url, tv.password, service.startError)
    }

    override fun onResume() {
        super.onResume()
        state.resumed = true
        state.lastInput = SystemClock.uptimeMillis()
        // Back from an app that was opened without a key press here (a remote's
        // Netflix button, a cast): show the dock, not the screensaver.
        state.ambient = false
        wakingKey = -1
        // Apps may have been installed or removed while we were away.
        loading?.cancel()
        loading = lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) { apps.installed() }
            // Order first: the dock focuses its first tile as soon as `installed` is set.
            state.dockIds = apps.dockIds(installed)
            state.installed = installed
            refreshPhone()          // the TV's address may have changed too
        }
    }

    override fun onPause() {
        state.resumed = false
        super.onPause()
    }

    // The Home button, once this is the home app.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) state.goHome()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        state.lastInput = SystemClock.uptimeMillis()
        // Screensaver: the first press only wakes the screen.
        if (state.ambient && event.action == KeyEvent.ACTION_DOWN) {
            state.ambient = false
            wakingKey = event.keyCode
            return true
        }
        if (event.keyCode == wakingKey) {
            if (event.action == KeyEvent.ACTION_UP) wakingKey = -1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun launch(app: TvApp) {
        // Google TV has no web browser until you install one, only a stub that says
        // "You don't have an app that can do this": say what to do instead.
        if (Shortcuts.isShortcut(app.id) && app.intent.`package` == null) {
            val handlers = packageManager.queryIntentActivities(app.intent, 0)
            if (handlers.all { it.activityInfo.packageName == "com.android.tv.frameworkpackagestubs" }) {
                Toast.makeText(this, "There's no web browser on this TV. Install one from Google Play to open websites.", Toast.LENGTH_LONG).show()
                return
            }
        }
        try {
            startActivity(app.intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
