package com.phoneclaw.app

import android.app.Application
import com.phoneclaw.app.di.AppGraph
import com.phoneclaw.app.notification.AndroidExplorationNotifier

class PhoneClawApplication : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidExplorationNotifier.createChannel(this)
        appGraph = AppGraph(this)
    }
}

