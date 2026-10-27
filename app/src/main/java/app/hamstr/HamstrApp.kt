package app.hamstr

import app.hamstr.widget.Widgets
import android.app.Application
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadStore
import app.hamstr.data.Playlists
import app.hamstr.data.Positions
import app.hamstr.data.Prefs
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import app.hamstr.engine.Network
import app.hamstr.update.Updater

class HamstrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        DownloadStore.init(this)
        Positions.init(this)
        Playlists.init(this)
        app.hamstr.data.Lyrics.init(this)
        Network.init(this)
        DownloadFiles.finishPending(this)
        DownloadFiles.fixAudioKinds()
        DownloadService.channels(this)
        Engine.start(this)
        Updater.init(this)
        Widgets.start(this)
        app.hamstr.ui.AppIcon.shortcuts(this, Prefs.settings.value.accent)
    }
}
