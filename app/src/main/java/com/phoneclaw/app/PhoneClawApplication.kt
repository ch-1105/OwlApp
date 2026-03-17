package com.phoneclaw.app

import android.app.Application
import com.phoneclaw.app.di.AppGraph

class PhoneClawApplication : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)
    }
}

