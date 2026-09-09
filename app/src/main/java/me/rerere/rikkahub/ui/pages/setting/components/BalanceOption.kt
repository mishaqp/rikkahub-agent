package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Refresh03
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.isJsonExprValid
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.openrouter.OpenRouterOAuthManager
import me.rerere.rikkahub.data.openrouter.OpenRouterOAuthStatus
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

private val ApiPathRegex = Regex("""^/[^ \t\n\r]*$""")

@Composable
fun SettingProviderBalanceOption(
    provider: ProviderSetting,
    balanceOption: BalanceOption,
    modifier: Modifier = Modifier,
    onEdit: (BalanceOption) -> Unit,
) {
    var expand by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        if (
            provider is ProviderSetting.OpenAI &&
            provider.baseUrl.contains("openrouter.ai", ignoreCase = true)
        ) {
            OpenRouterOAuthSection(provider)
        }

        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_balance_info),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    expand = !expand
                }
            ) {
                if (expand) {
                    Icon(
                        imageVector = HugeIcons.ArrowUp01,
                        contentDescription = null,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = null,
                    )
                }
            }
            Checkbox(
                checked = balanceOption.enabled,
                onCheckedChange = { onEdit(balanceOption.copy(enabled = it)) }
            )
        }
        AnimatedVisibility(visible = expand) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = balanceOption.apiPath,
                    onValueChange = { onEdit(balanceOption.copy(apiPath = it)) },
                    label = { Text(stringResource(R.string.setting_provider_page_balance_api_path)) },
                    isError = !balanceOption.apiPath.matches(ApiPathRegex),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = balanceOption.resultPath,
                    onValueChange = { onEdit(balanceOption.copy(resultPath = it)) },
                    label = { Text(stringResource(R.string.setting_provider_page_balance_json_key)) },
                    isError = !isJsonExprValid(balanceOption.resultPath),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
                )
                IconButton(
                    onClick = {
                        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                        if (defaultProvider != null) {
                            onEdit(defaultProvider.balanceOption.copy())
                        } else {
                            onEdit(BalanceOption())
                        }
                    }
                ) {
                    Icon(HugeIcons.Refresh03, null)
                }
            }
        }
    }
}

@Composable
private fun OpenRouterOAuthSection(provider: ProviderSetting.OpenAI) {
    val context = LocalContext.current
    val appScope = koinInject<AppScope>()
    val client = koinInject<OkHttpClient>()
    val json = koinInject<Json>()
    val settingsStore = koinInject<SettingsStore>()
    val toaster = LocalToaster.current
    val oauthManager = remember(context.applicationContext, appScope, client, json, settingsStore) {
        OpenRouterOAuthManager(
            context = context.applicationContext,
            scope = appScope,
            client = client,
            json = json,
            settingsStore = settingsStore,
        )
    }
    val oauthStatus by oauthManager.status.collectAsStateWithLifecycle()
    val waiting = oauthStatus is OpenRouterOAuthStatus.Waiting

    LaunchedEffect(oauthStatus) {
        when (val status = oauthStatus) {
            is OpenRouterOAuthStatus.Success -> {
                toaster.show("OpenRouter подключён через OAuth", type = ToastType.Success)
                oauthManager.consumeResult()
            }

            is OpenRouterOAuthStatus.Error -> {
                toaster.show(status.message, type = ToastType.Error)
                oauthManager.consumeResult()
            }

            else -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "OpenRouter OAuth",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (provider.apiKey.isBlank()) {
                "Войдите в OpenRouter — приложение само получит и сохранит отдельный API-ключ."
            } else {
                "API-ключ подключён. Можно войти повторно, чтобы выпустить новый ключ."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { oauthManager.startLogin(provider.id) },
            enabled = !waiting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    waiting -> "Ожидание входа…"
                    provider.apiKey.isBlank() -> "Войти через OpenRouter"
                    else -> "Повторный вход через OpenRouter"
                }
            )
        }
    }
}
