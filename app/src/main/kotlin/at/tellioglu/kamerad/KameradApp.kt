package at.tellioglu.kamerad

import android.app.Application
import at.tellioglu.kamerad.gopro.GoProController

class KameradApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GoProController.init(this)
    }
}
