package dev.nasexplorer

import android.app.Application

class NasApp : Application() {
    lateinit var store: ProfileStore
    lateinit var pool: SessionPool
    lateinit var transfers: TransferManager
    lateinit var thumbs: ThumbLoader

    override fun onCreate() {
        super.onCreate()
        HostKeys.init(this)
        store = ProfileStore(this)
        pool = SessionPool()
        transfers = TransferManager(this, pool)
        thumbs = ThumbLoader(this, pool)
    }
}
