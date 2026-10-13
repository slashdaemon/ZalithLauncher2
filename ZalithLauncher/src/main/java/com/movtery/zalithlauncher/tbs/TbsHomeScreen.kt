package com.movtery.zalithlauncher.tbs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.screens.content.elements.PlayerFace

/**
 * TBS-only home: who is signed in, whether the TBS instance is ready, and one Play button.
 * Everything else (versions, downloads, files) lives behind Settings → "Show all launcher features".
 */
@Composable
fun TbsHomeScreen(
    provisionViewModel: TbsProvisionViewModel,
    onSignIn: () -> Unit,
    onManageAccount: () -> Unit,
    onPlay: (com.movtery.zalithlauncher.game.version.installed.Version) -> Unit,
    onRetrySetup: () -> Unit,
) {
    val context = LocalContext.current
    val profile = TbsProfile.load(context)
    val account by AccountsManager.currentAccountFlow.collectAsStateWithLifecycle()
    val versions by VersionsManager.versions.collectAsStateWithLifecycle()
    val tbsVersion = versions.firstOrNull { it.getVersionName() == profile.instance && it.isValid() }
    val state = provisionViewModel.state

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("The Block Survival", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        val acc = account
        if (acc == null) {
            FilledTonalButton(onClick = onSignIn) { Text("Sign in with Microsoft") }
            Spacer(Modifier.height(6.dp))
            Text("A Minecraft: Java Edition account is needed to play.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerFace(account = acc, avatarSize = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(acc.username, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onManageAccount) { Text("Change") }
            }
        }
        Spacer(Modifier.height(24.dp))

        val statusText = when (state) {
            TbsProvisionState.Ready -> null
            is TbsProvisionState.Error -> "Setup didn't finish."
            else -> "Setting up The Block Survival…"
        }
        statusText?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        if (state is TbsProvisionState.Error) {
            TextButton(onClick = onRetrySetup) { Text("Try again") }
        }

        Button(
            modifier = Modifier.width(260.dp).height(56.dp),
            enabled = acc != null && tbsVersion != null && state == TbsProvisionState.Ready,
            onClick = { tbsVersion?.let(onPlay) }
        ) {
            Text("Play TBS", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "${profile.server} · Minecraft ${profile.mc} · StreamCraft ${UnpackStreamCraftTask.stagedVersion() ?: profile.streamcraft}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
