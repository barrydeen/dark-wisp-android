package com.darkwisp.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.darkwisp.app.R
import com.darkwisp.app.repo.DeepLApiClient
import com.darkwisp.app.repo.DeepLKeyStore
import com.darkwisp.app.repo.InterfacePreferences
import com.darkwisp.app.repo.LocaleRepository
import com.darkwisp.app.repo.InterfacePreferences.TranslationEngine
import kotlinx.coroutines.launch

@Composable
fun TranslationSettingsSection(interfacePrefs: InterfacePreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyStore = remember(context) {
        runCatching { DeepLKeyStore(context.applicationContext) }.getOrNull()
    }
    val apiClient = remember { DeepLApiClient() }

    var targetLanguage by remember { mutableStateOf(interfacePrefs.getTranslationTargetLanguage()) }
    var targetMenuExpanded by remember { mutableStateOf(false) }
    var translationEngine by remember { mutableStateOf(interfacePrefs.getTranslationEngine()) }
    var apiKey by remember(keyStore) { mutableStateOf(keyStore?.getApiKey().orEmpty()) }
    var showApiKey by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.translation_settings_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.translation_settings_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.translation_target_language),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            Box {
                OutlinedButton(onClick = { targetMenuExpanded = true }) {
                    Text(LocaleRepository.getLanguageDisplayName(targetLanguage))
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null
                    )
                }
                DropdownMenu(
                    expanded = targetMenuExpanded,
                    onDismissRequest = { targetMenuExpanded = false }
                ) {
                    LocaleRepository.supportedLanguages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language.displayName) },
                            onClick = {
                                targetLanguage = language.code
                                interfacePrefs.setTranslationTargetLanguage(language.code)
                                targetMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.translation_engine),
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        translationEngine = TranslationEngine.ON_DEVICE
                        interfacePrefs.setTranslationEngine(translationEngine)
                        feedback = null
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = translationEngine == TranslationEngine.ON_DEVICE,
                    onClick = {
                        translationEngine = TranslationEngine.ON_DEVICE
                        interfacePrefs.setTranslationEngine(translationEngine)
                        feedback = null
                    }
                )
                Text(
                    text = stringResource(R.string.translation_engine_mlkit),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        translationEngine = TranslationEngine.DEEPL
                        interfacePrefs.setTranslationEngine(translationEngine)
                        feedback = null
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = translationEngine == TranslationEngine.DEEPL,
                    onClick = {
                        translationEngine = TranslationEngine.DEEPL
                        interfacePrefs.setTranslationEngine(translationEngine)
                        feedback = null
                    }
                )
                Text(
                    text = stringResource(R.string.translation_engine_deepl),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (translationEngine == TranslationEngine.DEEPL) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.translation_deepl_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                if (keyStore == null) {
                    Text(
                        text = stringResource(R.string.translation_deepl_storage_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            feedback = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.translation_deepl_api_key)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(
                                        if (showApiKey) R.string.translation_deepl_hide_key
                                        else R.string.translation_deepl_show_key
                                    )
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                keyStore.saveApiKey(apiKey.trim())
                                feedback = context.getString(R.string.translation_deepl_key_saved)
                                feedbackIsError = false
                            },
                            enabled = apiKey.isNotBlank() && !isChecking
                        ) {
                            Text(stringResource(R.string.translation_deepl_save_key))
                        }
                        OutlinedButton(
                            onClick = {
                                val key = apiKey.trim()
                                if (key.isNotEmpty()) {
                                    keyStore.saveApiKey(key)
                                    isChecking = true
                                    feedback = context.getString(R.string.translation_deepl_testing)
                                    feedbackIsError = false
                                    scope.launch {
                                        try {
                                            val usage = apiClient.getUsage(key)
                                            feedback = context.getString(
                                                R.string.translation_deepl_usage,
                                                usage.characterCount.toString(),
                                                usage.characterLimit.toString()
                                            )
                                            feedbackIsError = false
                                        } catch (e: Exception) {
                                            feedback = e.message
                                                ?: context.getString(R.string.translation_deepl_test_error)
                                            feedbackIsError = true
                                        } finally {
                                            isChecking = false
                                        }
                                    }
                                }
                            },
                            enabled = apiKey.isNotBlank() && !isChecking
                        ) {
                            Text(
                                stringResource(
                                    if (isChecking) R.string.translation_deepl_testing
                                    else R.string.translation_deepl_test_key
                                )
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            keyStore.clearApiKey()
                            apiKey = ""
                            feedback = context.getString(R.string.translation_deepl_key_removed)
                            feedbackIsError = false
                        },
                        enabled = keyStore.getApiKey() != null && !isChecking
                    ) {
                        Text(stringResource(R.string.translation_deepl_remove_key))
                    }
                    feedback?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (feedbackIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }
    }
}
