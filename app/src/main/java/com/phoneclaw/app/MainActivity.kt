package com.phoneclaw.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.phoneclaw.app.ui.PhoneClawApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appGraph = (application as PhoneClawApplication).appGraph

        setContent {
            PhoneClawApp(appGraph = appGraph)
        }
    }
}

