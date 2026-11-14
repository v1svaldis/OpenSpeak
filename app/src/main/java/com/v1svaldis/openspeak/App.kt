package com.v1svaldis.openspeak

import android.app.Application
import com.v1svaldis.openspeak.ts.TsSession

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TsSession.init(this)
    }
}
