package com.jezrel.syncnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jezrel.syncnotes.ui.navigation.SyncNotesNavDisplay
import com.jezrel.syncnotes.ui.theme.SyncNotesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncNotesTheme {
                // A themed Surface behind every destination: sets the background and the default
                // content color, so text outside a Scaffold (e.g. placeholders) stays readable in
                // dark mode, and Nav3 transitions slide over a consistent background.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SyncNotesNavDisplay()
                }
            }
        }
    }
}