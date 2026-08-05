package de.mafo.hilt.provider.sample.android

import android.os.Bundle
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
        setContentView(TextView(this).apply { text = "${greeting.text} ($buildFlavour)" })
    }
}
