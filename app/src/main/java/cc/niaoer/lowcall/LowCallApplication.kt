package cc.niaoer.lowcall

import android.app.Application

class LowCallApplication : Application() {
    lateinit var appContainer: AppContainer

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
