package ts4droid.app

import android.app.Application
import ts4droid.app.ts.TsSession

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TsSession.init(this)
    }
}
