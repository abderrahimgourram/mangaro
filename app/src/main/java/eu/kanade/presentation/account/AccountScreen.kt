package eu.kanade.presentation.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import mihon.domain.account.AccountFoundation
import mihon.domain.account.AccountOperation
import mihon.domain.account.AccountSession
import mihon.domain.account.MangaroProfile
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AccountScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val account = remember { Injekt.get<AccountFoundation>() }
        val session by account.session.collectAsState()
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf<String?>(null) }
        var submitting by remember { mutableStateOf(false) }
        val loading = session == AccountSession.Loading || submitting
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).safeDrawingPadding()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع", tint = Color(0xFFD6C9E0))
                    }
                    Text("الحساب", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(72.dp))
                Text("Mangaro", style = MaterialTheme.typography.headlineSmall, color = MangaroDesignSystem.GoldPrimary, fontWeight = FontWeight.Bold)
                when (val current = session) {
                    is AccountSession.Authenticated -> {
                        Text(current.profile.displayName ?: current.profile.username.orEmpty(), color = Color.White)
                        current.profile.email?.let { Text(it, color = Color(0xFFB7A9C4)) }
                    }
                    else -> {
                        Text("أنت تستخدم Mangaro كضيف", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("سجّل الدخول لحفظ مكتبتك والتفاعل مع القراء", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodyMedium)
                        Text("يمكنك القراءة والتنزيل وتنظيم مكتبتك كضيف. تبقى بياناتك محفوظة على هذا الجهاز.",
                            color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = account.auth.googleSignInAvailable && !loading,
                            onClick = {
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        when (val result = account.auth.signInWithGoogle()) {
                                            AccountOperation.NotConfigured -> error = "تسجيل الدخول غير متاح حاليًا"
                                            is AccountOperation.Failed -> error = result.message
                                            AccountOperation.Completed -> Unit // Authentication is determined only by the session stream.
                                        }
                                    } finally { submitting = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MangaroDesignSystem.GoldPrimary, contentColor = MangaroDesignSystem.BackgroundDark,
                                disabledContainerColor = MangaroDesignSystem.SurfaceHigh, disabledContentColor = Color(0xFFB7A9C4),
                            ),
                        ) {
                            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("تسجيل الدخول باستخدام Google")
                        }
                        if (!account.auth.googleSignInAvailable) Text("تسجيل الدخول والمزامنة سيتوفران لاحقًا", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountDrawerArea(session: AccountSession, onLogin: () -> Unit, onProfile: ((MangaroProfile) -> Unit)? = null) {
    val profile = (session as? AccountSession.Authenticated)?.profile
    Surface(shape = RoundedCornerShape(16.dp), color = MangaroDesignSystem.SurfaceHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (profile?.avatarUrl != null) AsyncImage(profile.avatarUrl, null, modifier = Modifier.size(44.dp))
                else Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(profile?.displayName ?: profile?.username ?: "MANGARO", color = MangaroDesignSystem.GoldPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(profile?.email ?: "أنت تستخدم Mangaro كضيف", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                }
            }
            when (session) {
                is AccountSession.Authenticated -> TextButton(enabled = onProfile != null, onClick = { onProfile?.invoke(session.profile) }) {
                    Text("الملف الشخصي", color = MangaroDesignSystem.GoldPrimary)
                }
                AccountSession.Loading -> CircularProgressIndicator(Modifier.size(20.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
                AccountSession.Guest -> {
                    Text("سجّل الدخول لحفظ مكتبتك والتفاعل مع القراء", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onLogin) { Text("تسجيل الدخول", color = MangaroDesignSystem.GoldPrimary) }
                }
            }
        }
    }
}
