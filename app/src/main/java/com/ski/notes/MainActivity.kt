package com.ski.notes

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.ski.notes.ui.AppRoot
import com.ski.notes.ui.theme.SkiNotesTheme

class MainActivity : ComponentActivity() {
    /** A PDF or Word file handed to us by another app. */
    private val incoming = mutableStateOf<Pair<Uri, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crash = if (savedInstanceState == null) CrashLog.take(this) else null
        if (savedInstanceState == null) incoming.value = extract(intent)
        setContent {
            SkiNotesTheme {
                AppRoot(initialCrash = crash, incoming = incoming.value, onIncomingHandled = { incoming.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extract(intent)?.let { incoming.value = it }
    }

    private fun extract(intent: Intent?): Pair<Uri, String?>? {
        intent ?: return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return null
        return uri to intent.type
    }
}
