package com.github.pwittchen.reactivebeacons.kotlinapp

import android.content.Context
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication
import com.google.firebase.FirebaseApp

class App: MultiDexApplication(){

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}