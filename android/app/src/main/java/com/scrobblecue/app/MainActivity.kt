package com.scrobblecue.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.scrobblecue.app.data.credentials.SessionRepository
import com.scrobblecue.app.ui.ScrobbleCueApp
import com.scrobblecue.app.ui.theme.ScrobbleCueTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The app's only activity. It also receives the sign-in link at the end of a connect flow. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: SessionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a rotation the intent is the same one already handled
        if (savedInstanceState == null) handleSignInLink(intent)
        setContent {
            ScrobbleCueTheme {
                ScrobbleCueApp()
            }
        }
    }

    // singleTask: when the browser opens the sign-in link, the running activity receives it here
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSignInLink(intent)
    }

    private fun handleSignInLink(intent: Intent) {
        val link = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW } ?: return
        lifecycleScope.launch { session.receive(link) }
    }
}
