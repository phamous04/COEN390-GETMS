package com.example.app.ui.screens

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ReBandLogo
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.Teal

@Composable
fun LoginScreen(viewModel: AuthViewModel, onCreateAccount: () -> Unit) {
    val busy by viewModel.busy.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    AuthLayout(
        title = "Welcome back",
        subtitle = "Log in to continue your recovery exercises.",
        viewModel = viewModel,
    ) {
        EmailField(email) { email = it }
        PasswordField(password, label = "Password", imeAction = ImeAction.Done) { password = it }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            "Log in",
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
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    AuthLayout(
        title = "Create your account",
        subtitle = "Sign up to start your guided rehabilitation.",
        viewModel = viewModel,
    ) {
        EmailField(email) { email = it }
        PasswordField(password, label = "Password", imeAction = ImeAction.Next, hint = "At least 8 characters") {
            password = it
        }
        PasswordField(confirm, label = "Confirm password", imeAction = ImeAction.Done) { confirm = it }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            "Create account",
            onClick = { viewModel.signUp(email, password, confirm) },
            enabled = viewModel.configured,
            loading = busy,
        )
        SwitchAuthLink("Already have an account?", "Log in") {
            viewModel.clearMessages()
            onBackToLogin()
        }
    }
}

@Composable
private fun AuthLayout(
    title: String,
    subtitle: String,
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
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
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
