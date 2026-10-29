package com.ski.notes

import android.app.Application

class SkiNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
