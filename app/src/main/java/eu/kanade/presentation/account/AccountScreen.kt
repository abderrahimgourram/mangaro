package eu.kanade.presentation.account

import android.util.Patterns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R

// Account presentation is deliberately independent of manga, source and database state.
enum class AccountMode { CREATE, LOGIN }
data class AccountUser(val userId: String, val username: String, val email: String, val avatarUrl: String? = null)
sealed interface AccountUiState {
    data object Guest : AccountUiState
    data object Loading : AccountUiState
    data class LoggedIn(val user: AccountUser) : AccountUiState
    data class Error(val message: String) : AccountUiState
}

/** Future controller hooks. Credentials are transient inputs, never persisted by this UI. */
interface AccountActions {
    fun createAccount(username: String, email: String, password: String)
    fun signIn(email: String, password: String)
    fun resetPassword(email: String)
}

class AccountScreen(private val initialMode: AccountMode) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // No controller exists yet: all submission/reset actions remain disabled.
        AccountContent(initialMode, AccountUiState.Guest, actions = null, onBack = { navigator.pop() })
    }
}

@Composable
fun AccountDrawerArea(
    state: AccountUiState,
    onCreate: () -> Unit,
    onLogin: () -> Unit,
    onProfile: ((AccountUser) -> Unit)? = null,
) {
    val user = (state as? AccountUiState.LoggedIn)?.user
    Surface(shape = RoundedCornerShape(16.dp), color = MangaroDesignSystem.SurfaceHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (user?.avatarUrl != null) {
                    AsyncImage(user.avatarUrl, null, modifier = Modifier.size(44.dp))
                } else {
                    Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(44.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(user?.username ?: "MANGARO", color = MangaroDesignSystem.GoldPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(user?.email ?: "أنت تستخدم Mangaro كضيف", color = Color(0xFFB7A9C4),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            when (state) {
                is AccountUiState.LoggedIn -> TextButton(enabled = onProfile != null, onClick = { onProfile?.invoke(state.user) }) {
                    Text("الملف الشخصي", color = MangaroDesignSystem.GoldPrimary)
                }
                AccountUiState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
                else -> {
                    if (state is AccountUiState.Error) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = onCreate, shape = RoundedCornerShape(10.dp), colors = accountButtonColors()) { Text("إنشاء حساب") }
                        TextButton(onClick = onLogin) { Text("تسجيل الدخول", color = Color(0xFFD6C9E0)) }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountContent(initialMode: AccountMode, state: AccountUiState, actions: AccountActions?, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(initialMode) }
    // Intentionally remember, not rememberSaveable: no credentials in saved instance state.
    var username by remember(mode) { mutableStateOf("") }
    var email by remember(mode) { mutableStateOf("") }
    var password by remember(mode) { mutableStateOf("") }
    var confirmation by remember(mode) { mutableStateOf("") }
    val create = mode == AccountMode.CREATE
    val loading = state == AccountUiState.Loading
    val usernameError = if (username.trim().length < 3) "أدخل اسم مستخدم من 3 أحرف على الأقل" else null
    val emailError = if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) "أدخل بريدًا إلكترونيًا صحيحًا" else null
    val passwordError = when {
        password.isEmpty() -> "أدخل كلمة المرور"
        create && (password.length < 8 || password.none(Char::isLetter) || password.none(Char::isDigit)) -> "استخدم 8 أحرف على الأقل مع حرف ورقم"
        else -> null
    }
    val confirmationError = if (confirmation.isEmpty() || confirmation != password) "كلمتا المرور غير متطابقتين" else null
    val valid = emailError == null && passwordError == null && (!create || (usernameError == null && confirmationError == null))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).safeDrawingPadding()
                .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع", tint = Color(0xFFD6C9E0)) }
                Text("الحساب", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(64.dp))
            Text(if (create) "إنشاء حساب" else "تسجيل الدخول", style = MaterialTheme.typography.headlineSmall,
                color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountMode.entries.forEach { item ->
                    TextButton(onClick = { mode = item }, enabled = !loading) {
                        Text(if (item == AccountMode.CREATE) "إنشاء حساب" else "تسجيل الدخول",
                            color = if (mode == item) MangaroDesignSystem.GoldPrimary else Color(0xFFB7A9C4))
                    }
                }
            }
            if (create) AccountField("اسم المستخدم", username, { username = it }, usernameError, enabled = !loading)
            AccountField("البريد الإلكتروني", email, { email = it }, emailError, type = KeyboardType.Email, enabled = !loading)
            AccountField("كلمة المرور", password, { password = it }, passwordError, secret = true, enabled = !loading)
            if (create) {
                Text("8 أحرف على الأقل، تتضمن حرفًا ورقمًا", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB7A9C4))
                AccountField("تأكيد كلمة المرور", confirmation, { confirmation = it }, confirmationError, secret = true, enabled = !loading)
            }
            if (state is AccountUiState.Error) Text(state.message, color = MaterialTheme.colorScheme.error)
            if (actions == null) Text("خدمات الحساب ستتوفر لاحقًا", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7A9C4))
            Button(
                onClick = {
                    if (valid && !loading) {
                        if (create) actions?.createAccount(username.trim(), email.trim(), password)
                        else actions?.signIn(email.trim(), password)
                    }
                },
                enabled = actions != null && valid && !loading,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = accountButtonColors(),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (create) "إنشاء الحساب" else "تسجيل الدخول")
            }
            if (!create) TextButton(onClick = { actions?.resetPassword(email.trim()) }, enabled = actions != null && emailError == null && !loading) {
                Text("نسيت كلمة المرور؟", color = Color(0xFFB7A9C4))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun accountButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MangaroDesignSystem.GoldPrimary, contentColor = Color(0xFF140E1B),
    disabledContainerColor = MangaroDesignSystem.SurfaceHigh, disabledContentColor = Color(0xFF9F90AC),
)

@Composable
private fun AccountField(
    label: String, value: String, onChange: (String) -> Unit, error: String?,
    secret: Boolean = false, type: KeyboardType = KeyboardType.Text, enabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    var focusedOnce by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        label = { Text(label) }, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (it.isFocused) focusedOnce = true else if (focusedOnce) touched = true
        },
        isError = touched && error != null,
        supportingText = if (touched && error != null) {{ Text(error) }} else null,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {{
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (visible) "إخفاء كلمة المرور" else "إظهار كلمة المرور", Modifier.size(20.dp))
            }
        }} else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else type,
            imeAction = ImeAction.Next, autoCorrectEnabled = if (secret || type == KeyboardType.Email) false else null),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MangaroDesignSystem.SurfaceDark, unfocusedContainerColor = MangaroDesignSystem.SurfaceDark,
            focusedBorderColor = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.65f), unfocusedBorderColor = Color(0x406D557B),
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Color(0xFFD6C9E0), unfocusedLabelColor = Color(0xFFB7A9C4),
        ),
    )
}
