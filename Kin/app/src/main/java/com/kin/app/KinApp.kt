package com.kin.app

import android.app.Application
import com.google.firebase.FirebaseApp

class KinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
