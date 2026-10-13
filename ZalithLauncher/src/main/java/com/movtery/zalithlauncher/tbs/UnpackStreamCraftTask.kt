package com.movtery.zalithlauncher.tbs

import android.content.Context
import com.movtery.zalithlauncher.ZLApplication
import com.movtery.zalithlauncher.components.UnpackSingleTask
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.device.Architecture
import java.io.File

/**
 * Stages the StreamCraft jar bundled in the APK (assets/tbs/streamcraft/, filled by the build's
 * stageStreamCraft task) under files/components/streamcraft/, keeping only this device's ABI:
 * streamcraft-android-arm64.jar on devices, streamcraft-android-x86_64.jar in the PC emulator.
 * The TBS provisioner copies it into the TBS instance's mods folder.
 */
class UnpackStreamCraftTask(context: Context) : UnpackSingleTask(
    context = context,
    rootDir = PathManager.DIR_COMPONENTS,
    assetsDirName = "tbs/streamcraft",
    fileDirName = DIR_NAME
) {
    override suspend fun moreProgress(file: File) {
        if (file.extension == "jar" && file.name != deviceJarName()) file.delete()
    }

    companion object {
        const val DIR_NAME = "streamcraft"

        fun deviceJarName(): String =
            "streamcraft-android-${Architecture.archAsString(ZLApplication.DEVICE_ARCHITECTURE)}.jar"

        /** The staged jar for this device, or null when the splash has not unpacked it. */
        fun stagedJar(): File? =
            File(File(PathManager.DIR_COMPONENTS, DIR_NAME), deviceJarName()).takeIf { it.isFile }

        /** The StreamCraft version the staged jar belongs to (the asset's version file). */
        fun stagedVersion(): String? =
            File(File(PathManager.DIR_COMPONENTS, DIR_NAME), "version").takeIf { it.isFile }
                ?.readLines()?.firstOrNull()?.trim()
    }
}
