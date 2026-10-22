package com.medilocker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medilocker.crypto.WrappedKey

/** Section label, matching the mono eyebrow used throughout the design. */
@Composable
private fun Eyebrow(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace, color = color)

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    border: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit
) = Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, border)
) { Column(Modifier.padding(18.dp), content = content) }

// ---------------------------------------------------------------- first run

@Composable
fun FirstRunScreen(onCreate: (CharArray) -> Unit, busy: Boolean, error: String?) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val longEnough = pw.length >= WrappedKey.MIN_PASSWORD_LENGTH
    val matches = pw.isNotEmpty() && pw == confirm

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        Eyebrow("Set up · once")
        Text("Your records stay\non this phone", style = MaterialTheme.typography.displaySmall)
        Text(
            "There is no account and nothing is uploaded. This password unlocks a vault that only exists here.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = pw, onValueChange = { pw = it },
            label = { Text("Password") },
            supportingText = {
                Text(
                    if (longEnough) "Long enough"
                    else "At least ${WrappedKey.MIN_PASSWORD_LENGTH} characters — ${pw.length} so far",
                    color = if (longEnough) secureColor() else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it },
            label = { Text("Confirm password") },
            isError = confirm.isNotEmpty() && !matches,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        error?.let {
            Card(border = MaterialTheme.colorScheme.error) {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(
            onClick = { onCreate(pw.toCharArray()) },
            enabled = longEnough && matches && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp)); Text("Creating your vault…")
            } else Text("Create vault")
        }
        Text(
            "Creating the vault runs a deliberately slow key derivation. A second or two here is what makes guessing your password expensive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- recovery code

@Composable
fun RecoveryCodeScreen(code: String, onDone: () -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        Eyebrow("Write this down", attentionColor())
        Text("Your recovery code", style = MaterialTheme.typography.displaySmall)
        Text(
            "This is the only way back in if you forget your password. It is shown once and is never stored — nobody, including us, can recover it for you.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(border = attentionColor()) {
            Text(code, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(acknowledged, { acknowledged = it })
            Text("I have written it down somewhere safe", style = MaterialTheme.typography.bodyLarge)
        }
        Button(onClick = onDone, enabled = acknowledged,
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
    }
}

// ---------------------------------------------------------------- unlock

@Composable
fun UnlockScreen(
    onUnlock: (CharArray) -> Unit,
    onForgot: () -> Unit,
    onBiometric: (() -> Unit)? = null,
    busy: Boolean,
    error: String?
) {
    var pw by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Eyebrow("Locked")
        Spacer(Modifier.height(8.dp))
        Text("MediLocker", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pw, onValueChange = { pw = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            singleLine = true, isError = error != null,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onUnlock(pw.toCharArray()) }, enabled = pw.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp)); Text("Unlocking…")
            } else Text("Unlock")
        }
        if (onBiometric != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onBiometric,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Use fingerprint or face") }
        }
        TextButton(onClick = onForgot, modifier = Modifier.fillMaxWidth()) {
            Text("Forgot password? Use your recovery code")
        }
    }
}

/**
 * Offered once, straight after the recovery code. Declining is a real choice —
 * the password path is unaffected either way.
 */
@Composable
fun BiometricOfferScreen(onEnable: () -> Unit, onSkip: () -> Unit, busy: Boolean, error: String?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        Eyebrow("One last thing")
        Text("Unlock with your\nfingerprint?", style = MaterialTheme.typography.displaySmall)
        Text(
            "Faster than typing your password every time. Your password and recovery code keep working exactly as they do now.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card {
            Text(
                "The key is held by this phone's security hardware and never leaves it. " +
                    "If a new fingerprint is ever added to the device, this is switched off " +
                    "automatically and your password is required again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        error?.let {
            Card(border = MaterialTheme.colorScheme.error) {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onEnable, enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp)); Text("Enabling…")
            } else Text("Enable fingerprint unlock")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Not now — use my password")
        }
    }
}

@Composable
fun RecoveryUnlockScreen(
    onRecover: (code: String, newPassword: CharArray) -> Unit,
    onBack: () -> Unit, busy: Boolean, error: String?
) {
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val longEnough = pw.length >= WrappedKey.MIN_PASSWORD_LENGTH
    val matches = pw.isNotEmpty() && pw == confirm

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Recover your vault", style = MaterialTheme.typography.displaySmall)
        Text(
            "Enter the recovery code you wrote down when the vault was created, " +
                "and choose a new password. Your records are untouched — only the password changes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(value = code, onValueChange = { code = it },
            label = { Text("Recovery code") },
            supportingText = { Text("Dashes and spacing don't matter") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = pw, onValueChange = { pw = it },
            label = { Text("New password") },
            supportingText = {
                Text(if (longEnough) "Long enough"
                     else "At least ${WrappedKey.MIN_PASSWORD_LENGTH} characters — ${pw.length} so far")
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = confirm, onValueChange = { confirm = it },
            label = { Text("Confirm new password") },
            isError = confirm.isNotEmpty() && !matches,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { onRecover(code, pw.toCharArray()) },
            enabled = code.isNotBlank() && longEnough && matches && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp)); Text("Recovering…")
            } else Text("Unlock with recovery code")
        }
    }
}
