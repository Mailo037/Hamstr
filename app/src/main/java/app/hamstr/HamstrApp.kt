package app.hamstr

import android.app.Application
import app.hamstr.data.DownloadStore
import app.hamstr.data.Prefs
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import app.hamstr.update.Updater

class HamstrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        DownloadStore.init(this)
        DownloadService.channels(this)
        Engine.start(this)
        Updater.init(this)
    }
}
