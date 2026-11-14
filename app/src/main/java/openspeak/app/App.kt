package openspeak.app

import android.app.Application
import openspeak.app.ts.TsSession

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TsSession.init(this)
    }
}
