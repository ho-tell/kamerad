package at.tellioglu.kamerad

import android.app.Application
import at.tellioglu.kamerad.gopro.EventLog
import at.tellioglu.kamerad.gopro.GoProController

class KameradApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        GoProController.init(this)
    }
}
