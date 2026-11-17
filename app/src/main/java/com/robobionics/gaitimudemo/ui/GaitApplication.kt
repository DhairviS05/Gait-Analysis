package com.robobionics.gaitimudemo

import android.app.Application
import com.google.firebase.FirebaseApp

class GaitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}