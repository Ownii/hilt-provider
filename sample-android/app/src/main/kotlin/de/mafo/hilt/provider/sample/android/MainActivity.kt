package de.mafo.hilt.provider.sample.android

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import de.mafo.hilt.provider.sample.android.feature.Greeting
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var greeting: Greeting

    // Qualifier on the field, not the property – otherwise Dagger never sees it.
    @Inject
    @field:Named("build-flavour")
    lateinit var buildFlavour: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val injected = "${greeting.text} ($buildFlavour)"
        // Logged as well as shown, so the injection can be verified without looking at the screen.
        Log.i("hilt-provider", "injected: $injected")
        setContentView(
            TextView(this).apply {
                text = injected
                textSize = 20f
                setPadding(48, 48, 48, 48)
                // Centred, because targetSdk 36 means edge-to-edge: the content view starts at y=0
                // and anything near the top would sit behind the action bar.
                gravity = Gravity.CENTER
                // Explicit colours: the sample declares no theme, so on a device in dark mode the
                // default text colour would be white on a light window background.
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
            },
        )
    }
}
