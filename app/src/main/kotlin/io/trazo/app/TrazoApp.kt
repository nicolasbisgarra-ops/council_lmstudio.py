package io.trazo.app

import android.app.Application
import android.content.Context
import io.trazo.app.data.AiStore
import io.trazo.app.data.SettingsStore
import io.trazo.app.data.TrazoRepository

class TrazoApp : Application() {
    val repository: TrazoRepository by lazy { TrazoRepository(filesDir) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val ai: AiStore by lazy { AiStore(this) }
}

val Context.trazo: TrazoApp get() = applicationContext as TrazoApp
