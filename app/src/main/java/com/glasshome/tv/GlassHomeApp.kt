package com.glasshome.tv

import android.app.Application
import com.glasshome.tv.web.PhotoService

/** Starts the phone website with the app, so it's up whenever the home screen is. */
class GlassHomeApp : Application() {
    lateinit var service: PhotoService
        private set

    override fun onCreate() {
        super.onCreate()
        service = PhotoService(this).also { it.start() }
    }
}
