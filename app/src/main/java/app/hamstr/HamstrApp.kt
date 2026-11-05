package app.hamstr

import app.hamstr.data.Downloads
import app.hamstr.widget.Widgets
import android.app.Application
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadStore
import app.hamstr.data.Playlists
import app.hamstr.data.Positions
import app.hamstr.data.Prefs
import app.hamstr.data.init
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import app.hamstr.engine.Network
import app.hamstr.update.Updater
import app.hamstr.data.Status
import app.hamstr.data.Subscriptions
import app.hamstr.engine.Wakeups
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HamstrApp : Application() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(app.hamstr.AppLanguage.wrap(base))

    override fun onCreate() {
        super.onCreate()
        // The history is the biggest file: it is read beside the smaller ones, needed from finishPending on.
        DownloadStore.preload(filesDir)
        Prefs.init(this)
        Subscriptions.init(filesDir)
        Positions.init(this)
        Playlists.init(this)
        app.hamstr.data.Lyrics.init(this)
        Network.init(this)
        DownloadStore.init(this)
        DownloadFiles.finishPending(this)
        DownloadFiles.fixAudioKinds()
        DownloadService.channels(this)
        Downloads.control = DownloadService.control(this)
        Engine.start(this)
        Updater.init(this)
        Widgets.start(this)
        app.hamstr.nearby.PhoneNearby.start(this)
        app.hamstr.ui.AppIcon.shortcuts(this, Prefs.settings.value.accent)
        watchSchedule()
    }

    /**
     * The alarm follows the followed channels and the night's hours; a schedule changed in
     * Settings lets what it held go at once (or holds it again), even with the queue stopped.
     */
    private fun watchSchedule() {
        val scope = MainScope()
        scope.launch {
            // Only what the alarm depends on: an accent or a volume change sets no new alarm.
            combine(
                Subscriptions.all.map { list -> list.map { it.checkedAt } }.distinctUntilChanged(),
                Prefs.settings.map { Triple(it.nightOnly, it.nightFrom, it.nightTo) }.distinctUntilChanged(),
            ) { _, _ -> }.collect { Wakeups.plan(this@HamstrApp) }
        }
        scope.launch {
            Prefs.settings.map { listOf(it.nightOnly, it.nightFrom, it.nightTo, it.chargingOnly) }.distinctUntilChanged().drop(1).collect {
                if (DownloadStore.tasks.value.any { t -> t.status == Status.Queued && (t.stage?.held == true || t.stage == null) }) {
                    runCatching { DownloadService.send(this@HamstrApp, DownloadService.ACTION_WAKE) }
                }
            }
        }
    }
}
