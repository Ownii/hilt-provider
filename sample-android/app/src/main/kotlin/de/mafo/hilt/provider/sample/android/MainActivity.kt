package de.mafo.hilt.provider.sample.android

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import de.mafo.hilt.provider.sample.android.feature.Greeting
import de.mafo.hilt.provider.sample.android.feature.Handler
import de.mafo.hilt.provider.sample.android.feature.NavEntry
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

    /**
     * Filled from two `@Provide(multibinding = IntoSet)` declarations in two files of the library
     * module, so this also shows Hilt merging the two generated modules into one set.
     *
     * `@JvmSuppressWildcards` is required: Kotlin compiles `Set<NavEntry>` to
     * `Set<? extends NavEntry>`, which is not the type Dagger bound.
     */
    @Inject
    lateinit var navEntries: Set<@JvmSuppressWildcards NavEntry>

    /** The `@IntoMap` counterpart, keyed by the forwarded `@StringKey`. */
    @Inject
    lateinit var handlers: Map<String, @JvmSuppressWildcards Handler>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val routes = navEntries.map(NavEntry::route).sorted()
        val injected = "${greeting.text} ($buildFlavour)\nroutes: $routes\nhandlers: ${handlers.keys.sorted()}"
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
