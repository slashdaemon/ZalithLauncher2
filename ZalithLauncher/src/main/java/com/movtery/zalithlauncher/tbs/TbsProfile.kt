package com.movtery.zalithlauncher.tbs

import android.content.Context
import com.movtery.zalithlauncher.context.readAssetFile
import com.movtery.zalithlauncher.path.GLOBAL_JSON
import kotlinx.serialization.Serializable

/**
 * The TBS instance this launcher provisions (assets/tbs/profile.json). Bump it per TBS update:
 * it must match TBS-server's pack.toml (MC, Fabric loader, Fabric API) and the StreamCraft jars
 * the build stages into assets/tbs/streamcraft/.
 */
@Serializable
data class TbsProfile(
    val instance: String,
    val mc: String,
    val fabricLoader: String,
    val fabricApi: String,
    val server: String,
    val streamcraft: String,
) {
    companion object {
        private const val ASSET = "tbs/profile.json"

        @Volatile
        private var cached: TbsProfile? = null

        fun load(context: Context): TbsProfile =
            cached ?: GLOBAL_JSON.decodeFromString(serializer(), context.readAssetFile(ASSET))
                .also { cached = it }
    }
}
