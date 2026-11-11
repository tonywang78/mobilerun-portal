package com.carrun.caragent

import android.app.Application
import com.carrun.caragent.config.AgentPreferences

class CarAgentApplication : Application() {
    lateinit var preferences: AgentPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AgentPreferences(this)
    }
}
