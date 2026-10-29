package com.ski.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ski.notes.ui.AppRoot
import com.ski.notes.ui.theme.SkiNotesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crash = if (savedInstanceState == null) CrashLog.take(this) else null
        setContent {
            SkiNotesTheme { AppRoot(initialCrash = crash) }
        }
    }
}
