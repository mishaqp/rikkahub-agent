package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.rerere.rikkahub.RouteActivity

class CodexOAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.data?.getQueryParameter("target")
        startActivity(
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // OpenRouter reuses this already-registered deep link only to bring the current
                // RikkaHub task back to the foreground. Normal Codex callbacks keep the existing
                // behaviour and explicitly reopen the built-in Codex provider settings.
                if (target != "openrouter") {
                    putExtra(RouteActivity.EXTRA_OPEN_CODEX_SETTINGS, true)
                }
            }
        )
        finish()
    }
}
