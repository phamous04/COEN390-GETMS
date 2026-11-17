package com.example.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import com.example.app.ui.theme.TealDark
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ReBandLogo
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealLight

@Composable
fun LoginScreen(viewModel: AuthViewModel, onCreateAccount: () -> Unit) {
    val busy by viewModel.busy.collectAsState()
    val role by viewModel.role.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    AuthLayout(
        role = role,
        title = { if (it == LoginRole.Clinician) "Clinician login" else "Welcome back" },
        subtitle = {
            if (it == LoginRole.Clinician) "Log in to follow your patients' rehabilitation progress."
            else "Log in to continue your recovery exercises."
        },
        viewModel = viewModel,
    ) {
        EmailField(email) { email = it }
        PasswordField(password, label = "Password", imeAction = ImeAction.Done) { password = it }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            if (role == LoginRole.Clinician) "Log in as clinician" else "Log in",
            onClick = { viewModel.signIn(email, password) },
            enabled = viewModel.configured,
            loading = busy,
        )
        SwitchAuthLink("New to ReBand?", "Create an account") {
            viewModel.clearMessages()
            onCreateAccount()
        }
    }
}

@Composable
fun SignUpScreen(viewModel: AuthViewModel, onBackToLogin: () -> Unit) {
    val busy by viewModel.busy.collectAsState()
    val role by viewModel.role.collectAsState()
    val clinician = role == LoginRole.Clinician
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var fullName by rememberSaveable { mutableStateOf("") }

    AuthLayout(
        role = role,
        title = { if (it == LoginRole.Clinician) "Create a clinician account" else "Create your account" },
        subtitle = {
            if (it == LoginRole.Clinician) "Invite your patients and follow their progress once they agree to share it."
            else "Sign up to start your guided rehabilitation."
        },
        viewModel = viewModel,
    ) {
        AnimatedVisibility(visible = clinician, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Banner(
                "You'll get an invitation code to share with patients. You only see a patient's progress after they enter your code and agree to share it.",
                isError = false,
            )
        }
        OutlinedTextField(
            value = fullName,
            onValueChange = { fullName = it },
            label = { Text("Full name") },
            singleLine = true,
            supportingText = {
                Text(if (clinician) "Shown to patients who connect to you." else "Shown to a clinician only if you choose to connect.")
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        EmailField(email) { email = it }
        PasswordField(password, label = "Password", imeAction = ImeAction.Next, hint = "At least 8 characters") {
            password = it
        }
        PasswordField(confirm, label = "Confirm password", imeAction = ImeAction.Done) { confirm = it }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            if (clinician) "Create clinician account" else "Create account",
            onClick = { viewModel.signUp(email, password, confirm, fullName, clinician) },
            enabled = viewModel.configured,
            loading = busy,
        )
        SwitchAuthLink("Already have an account?", "Log in") {
            viewModel.clearMessages()
            onBackToLogin()
        }
    }
}

/** Sliding Patient / Clinician switch shared by login and sign-up. */
@Composable
private fun RoleToggle(selected: LoginRole, onSelect: (LoginRole) -> Unit) {
    val options = LoginRole.entries
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(TealLight)
            .padding(5.dp)
            .selectableGroup(),
    ) {
        val segment = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segment * options.indexOf(selected),
            animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
            label = "roleIndicator",
        )
        Box(
            Modifier
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Teal, TealDark))),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val isSelected = option == selected
                val color by animateColorAsState(if (isSelected) Color.White else Teal, label = "roleLabel")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = isSelected, onClick = { onSelect(option) }, role = Role.Tab),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (option == LoginRole.Clinician) "Clinician" else "Patient",
                        style = MaterialTheme.typography.titleMedium,
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthLayout(
    role: LoginRole,
    title: (LoginRole) -> String,
    subtitle: (LoginRole) -> String,
    viewModel: AuthViewModel,
    content: @Composable () -> Unit,
) {
    val error by viewModel.error.collectAsState()
    val info by viewModel.info.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ReBandLogo(large = true)
        Spacer(Modifier.height(12.dp))
        RoleToggle(role, viewModel::selectRole)
        Spacer(Modifier.height(4.dp))
        Crossfade(targetState = role, label = "authHeader") { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title(shown), style = MaterialTheme.typography.headlineMedium)
                Text(subtitle(shown), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (!viewModel.configured) {
            Banner("Supabase is not configured. Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to local.properties, then rebuild.", isError = true)
        }
        error?.let { Banner(it, isError = true) }
        info?.let { Banner(it, isError = false) }
        content()
    }
}

@Composable
private fun Banner(message: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else Teal
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(14.dp),
    )
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    value: String,
    label: String,
    imeAction: ImeAction,
    hint: String? = null,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        supportingText = hint?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show", color = Teal) }
        },
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchAuthLink(prompt: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick) { Text(action, color = Orange, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Teal,
    focusedLabelColor = Teal,
    cursorColor = Teal,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
)
