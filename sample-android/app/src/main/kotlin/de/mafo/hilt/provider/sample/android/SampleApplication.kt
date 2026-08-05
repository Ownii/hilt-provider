package de.mafo.hilt.provider.sample.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The Hilt root. It deliberately lives in a different Gradle module than the `@Provide`
 * declarations: only then does the build exercise Hilt's cross-module aggregation of the generated
 * `@InstallIn` modules.
 */
@HiltAndroidApp
class SampleApplication : Application()
