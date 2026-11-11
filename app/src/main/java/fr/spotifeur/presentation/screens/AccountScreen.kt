@file:Suppress("LocalContextGetResourceValueCall") // A language change recreates the activity.

package fr.spotifeur.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.spotifeur.AccountUi
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.ui.theme.GoogleSansRounded

@Composable
fun AccountScreen(model: SpotifeurModel, account: AccountUi) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var google by remember { mutableStateOf(false) }
    var googleFailed by remember { mutableStateOf(false) }
    if (google) {
        SpotifyGoogleSignIn(
            onSigned = { code, idToken -> google = false; googleFailed = false; model.connectSpotifyGoogle(code, idToken, SPOTIFY_GOOGLE_REDIRECT) },
            onFailed = { google = false; googleFailed = true },
            onClose = { google = false },
        )
        return
    }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            // The name alone at the top, the choices at the bottom within reach of the thumb.
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Spotifeur",
                    modifier = Modifier.padding(start = 4.dp, top = 72.dp, bottom = 40.dp),
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 64.sp,
                    lineHeight = 68.sp,
                    letterSpacing = (-1.5).sp,
                    color = colors.primary,
                )
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(32.dp), color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_source_spotify), null, Modifier.size(24.dp), tint = colors.primary)
                            Text("Spotify", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleLarge,
                                fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold)
                            Surface(shape = CircleShape, color = colors.surfaceContainerHighest) {
                                Text(ctx.getString(R.string.login_optional), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                            }
                        }
                        SignInForm(account, model, googleFailed) { googleFailed = false; google = true }
                    }
                }
                if (account.codeLength == null) FilledTonalButton(
                    onClick = model::continueWithoutAccount,
                    enabled = !account.working,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(ctx.getString(R.string.continue_without_account), style = MaterialTheme.typography.titleSmall) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SignInForm(account: AccountUi, model: SpotifeurModel, googleFailed: Boolean, onGoogle: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordMode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val working = account.working
    Column(Modifier.fillMaxWidth()) {
        run {
            val codeLength = account.codeLength
            when {
                codeLength != null -> {
                    Text(
                        account.codeEmail?.let { ctx.getString(R.string.code_prompt_email, it) }
                            ?: ctx.getString(R.string.code_prompt),
                        Modifier.padding(top = 16.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(codeLength) },
                        label = { Text(ctx.getString(R.string.code_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            autofill?.cancel()
                            focus.clearFocus()
                            model.submitCode(code)
                            code = ""
                        },
                        enabled = code.length == codeLength,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                    ) { Text(ctx.getString(R.string.code_confirm)) }
                    TextButton(onClick = model::disconnectSpotify) { Text(ctx.getString(R.string.action_cancel)) }
                }
                else -> {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(if (passwordMode) ctx.getString(R.string.login_user) else ctx.getString(R.string.login_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !working,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentType = ContentType.Username + ContentType.EmailAddress
                        },
                    )
                    if (passwordMode) OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(ctx.getString(R.string.login_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !working,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics {
                            contentType = ContentType.Password
                        },
                    )
                    Button(
                        onClick = {
                            if (!passwordMode) autofill?.cancel()
                            focus.clearFocus()
                            if (passwordMode) model.connectSpotify(username, password)
                            else model.connectSpotifyEmail(username)
                            password = ""
                        },
                        enabled = !working && username.isNotBlank() && (!passwordMode || password.isNotEmpty()),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                    ) {
                        Text(when {
                            account.working -> ctx.getString(R.string.login_connecting)
                            passwordMode -> ctx.getString(R.string.login_with_password)
                            else -> ctx.getString(R.string.login_with_code)
                        })
                    }
                    OutlinedButton(
                        onClick = onGoogle,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_google), null, Modifier.size(20.dp))
                        Text(ctx.getString(R.string.login_with_google), Modifier.padding(start = 10.dp))
                    }
                    TextButton(
                        onClick = { passwordMode = !passwordMode; password = "" },
                        enabled = !working,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text(if (passwordMode) ctx.getString(R.string.login_use_code) else ctx.getString(R.string.login_use_password)) }
                    if (account.working) TextButton(onClick = model::disconnectSpotify) { Text(ctx.getString(R.string.action_cancel)) }
                    (account.error ?: ctx.getString(R.string.spotify_google_failed).takeIf { googleFailed && !working })?.let {
                        Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
