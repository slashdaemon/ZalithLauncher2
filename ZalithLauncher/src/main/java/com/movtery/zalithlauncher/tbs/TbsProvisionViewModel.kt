package com.movtery.zalithlauncher.tbs

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricAPIVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersion
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.game.version.installed.SettingState
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import com.movtery.zalithlauncher.ui.screens.content.elements.TitleTaskFlowDialog
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "TbsProvision"

sealed interface TbsProvisionState {
    /** Not checked yet. */
    data object Idle : TbsProvisionState
    /** Looking at the installed instance. */
    data object Checking : TbsProvisionState
    /** Installing (or updating) Minecraft + Fabric + Fabric API for the TBS instance. */
    data object Installing : TbsProvisionState
    /** The TBS instance is installed and carries this APK's StreamCraft. */
    data object Ready : TbsProvisionState
    data class Error(val th: Throwable) : TbsProvisionState
}

/**
 * Keeps the "TBS" instance in line with assets/tbs/profile.json: installs it on first run, rebuilds
 * it in place when the profile's Minecraft or Fabric version changes (worlds and settings stay), and
 * swaps in the StreamCraft jar bundled with this APK whenever that changes.
 */
class TbsProvisionViewModel : ViewModel() {
    var state by mutableStateOf<TbsProvisionState>(TbsProvisionState.Idle)
        private set

    var installer by mutableStateOf<GameInstaller?>(null)
        private set

    fun ensure(context: Context) {
        if (state == TbsProvisionState.Checking || state == TbsProvisionState.Installing) return
        state = TbsProvisionState.Checking
        viewModelScope.launch {
            runCatching {
                val profile = TbsProfile.load(context)
                VersionsManager.waitForRefresh()
                val current = VersionsManager.versions.value.firstOrNull { it.getVersionName() == profile.instance }
                val info = current?.getVersionInfo()
                val loader = info?.loaderInfos?.firstOrNull { it.loader == ModLoader.FABRIC }?.version
                when {
                    current == null || !VersionsManager.isVersionExists(profile.instance, checkJson = true) ->
                        install(context, profile, existingGameHome = null)
                    info?.minecraftVersion != profile.mc || loader != profile.fabricLoader -> {
                        Logger.info(TAG, "Profile changed (${info?.minecraftVersion}/$loader -> ${profile.mc}/${profile.fabricLoader}); updating in place")
                        install(context, profile, existingGameHome = current.getGameHome())
                    }
                    else -> finish(profile)
                }
            }.onFailure { th ->
                Logger.error(TAG, "TBS provisioning failed", th)
                state = TbsProvisionState.Error(th)
            }
        }
    }

    private suspend fun install(context: Context, profile: TbsProfile, existingGameHome: String?) {
        state = TbsProvisionState.Installing
        val fabricApi = FabricAPIVersions.fetchVersionList(profile.mc)
            ?.firstOrNull { it.isVersion(profile.fabricApi) }
            ?: error("Fabric API ${profile.fabricApi} is not listed for Minecraft ${profile.mc}")
        val info = GameDownloadInfo(
            gameVersion = profile.mc,
            customVersionName = profile.instance,
            fabric = FabricVersion(inherit = profile.mc, version = profile.fabricLoader, stable = true),
            fabricAPI = fabricApi,
        )
        val onError: (Throwable) -> Unit = { th ->
            installer = null
            Logger.error(TAG, "TBS instance install failed", th)
            state = TbsProvisionState.Error(th)
        }
        val onDone: () -> Unit = {
            installer = null
            viewModelScope.launch {
                runCatching { finish(profile) }.onFailure(onError)
            }
        }
        installer = if (existingGameHome == null) {
            GameInstaller(context, info, viewModelScope).also {
                it.installGame(onInstalled = { onDone() }, onError = onError, onGameAlreadyInstalled = onDone)
            }
        } else {
            GameInstaller(context, info, viewModelScope, targetGameFolder = File(existingGameHome)).also {
                it.modifyVersion(onModified = onDone, onError = onError)
            }
        }
    }

    /** Isolation + auto-join, then this APK's StreamCraft into the instance's mods folder. */
    private suspend fun finish(profile: TbsProfile) {
        withContext(Dispatchers.IO) {
            val dir = File(getVersionsHome(), profile.instance)
            VersionConfig.parseConfig(dir).apply {
                isolationType = SettingState.ENABLE
                serverIp = profile.server
            }.save()

            val staged = UnpackStreamCraftTask.stagedJar()
                ?: error("The bundled StreamCraft jar is not unpacked (restart the launcher)")
            val stagedVersion = UnpackStreamCraftTask.stagedVersion() ?: profile.streamcraft
            val mods = VersionFolders.MOD.getDir(dir).apply { mkdirs() }
            val target = File(mods, "streamcraft-tbs-$stagedVersion.jar")
            mods.listFiles { f -> f.name.startsWith("streamcraft") && f.name.endsWith(".jar") && f != target }
                ?.forEach { old -> Logger.info(TAG, "Removing ${old.name}: ${old.delete()}") }
            if (!target.isFile || target.length() != staged.length() || !target.readBytes().contentEquals(staged.readBytes())) {
                staged.copyTo(target, overwrite = true)
                Logger.info(TAG, "StreamCraft $stagedVersion installed into ${profile.instance}")
            }
        }
        VersionsManager.refresh("[TBS]", profile.instance)
        state = TbsProvisionState.Ready
    }
}

/** Progress and error dialogs for [TbsProvisionViewModel]; rendered from MainActivity. */
@Composable
fun TbsProvisionOperation(viewModel: TbsProvisionViewModel, onRetry: () -> Unit) {
    when (val s = viewModel.state) {
        is TbsProvisionState.Installing -> {
            val installer = viewModel.installer ?: return
            val tasks by installer.tasksFlow.collectAsStateWithLifecycle()
            val log by installer.logOutput.collectAsStateWithLifecycle()
            if (tasks.isNotEmpty()) {
                TitleTaskFlowDialog(title = "Setting up The Block Survival", tasks = tasks, logOutput = log)
            }
        }
        is TbsProvisionState.Error -> SimpleAlertDialog(
            title = "Couldn't set up The Block Survival",
            text = (s.th.message ?: s.th.javaClass.simpleName) +
                "\n\nCheck your internet connection and try again.",
            confirmText = "Try again",
            onConfirm = onRetry,
            onDismiss = {}
        )
        else -> {}
    }
}
