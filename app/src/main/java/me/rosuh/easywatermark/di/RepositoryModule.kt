package me.rosuh.easywatermark.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.Dispatchers
import me.rosuh.easywatermark.data.db.AppDatabase
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.config_default_water_mark_text
import me.rosuh.easywatermark.ui.sharedString
import me.rosuh.easywatermark.utils.ktx.toWatermarkTileMode
import org.koin.dsl.module

val repositoryModule = module {
    single<UserConfigRepository> {
        UserConfigRepository(get<Context>().userDataStore)
    }

    single<WaterMarkRepository> {
        val context = get<Context>()
        WaterMarkRepository(
            dataStore = context.waterMarkDataStore,
            // Product default watermark text from composeResources (locale-aware via sharedString).
            defaultTextProvider = { sharedString(Res.string.config_default_water_mark_text) },
            // S4d-87: Android edge passes the SDK-gated legacy tile-id mapper (pre-S DECAL -> REPEAT).
            tileModeFromStorageId = { it.toWatermarkTileMode() },
            logError = { message -> Log.e("WaterMarkRepository", message) },
        )
    }

    single<MemorySettingRepo> {
        MemorySettingRepo()
    }

    single<TemplateRepository> {
        // S4d-92: nullable-DAO fallback unchanged; Dispatchers.IO injected here (commonMain repo can't
        // reference it on Native) so Android write threading stays byte-identical.
        TemplateRepository((getOrNull<AppDatabase>())?.templateDao(), Dispatchers.IO)
    }

}