package com.example.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.app.data.remote.AccountInfo
import com.example.app.data.remote.AccountRole
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.pairing.AccountLoadErrorScreen
import com.example.app.ui.pairing.AccountState
import com.example.app.ui.pairing.AccountViewModel
import com.example.app.ui.pairing.ClinicianAccountScreen
import com.example.app.ui.pairing.ClinicianCodeScreen
import com.example.app.ui.pairing.ClinicianDashboardScreen
import com.example.app.ui.pairing.ClinicianViewModel
import com.example.app.ui.pairing.ConfirmClinicianScreen
import com.example.app.ui.pairing.ConnectedPatientsScreen
import com.example.app.ui.pairing.EditNameDialog
import com.example.app.ui.pairing.EnterClinicianCodeScreen
import com.example.app.ui.pairing.MyClinicianScreen
import com.example.app.ui.pairing.PatientClinicianViewModel
import com.example.app.ui.pairing.PatientProgressScreen
import com.example.app.ui.pairing.PendingVerificationScreen
import com.example.app.ui.pairing.ReconnectionRequestsScreen
import com.example.app.ui.screens.AuthState
import com.example.app.ui.screens.AuthViewModel
import com.example.app.ui.BleSensorScreen
import com.example.app.ui.screens.DeviceScanScreen
import com.example.app.ui.screens.ExercisesScreen
import com.example.app.ui.screens.HistoryScreen
import com.example.app.ui.screens.HomeScreen
import com.example.app.ui.screens.LoadingScreen
import com.example.app.ui.screens.LoginRole
import com.example.app.ui.screens.LoginScreen
import com.example.app.ui.screens.SettingsScreen
import com.example.app.ui.screens.SignUpScreen
import com.example.app.ui.screens.WearableViewModel
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealLight

object Routes {
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val HOME = "home"
    const val EXERCISES = "exercises"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DEVICES = "devices"
    // Teammates' live sensor/recording screen, opened by Start Exercise.
    const val SENSOR = "sensor"

    // Patient: clinician pairing
    const val MY_CLINICIAN = "patient/clinician"
    const val ENTER_CLINICIAN_CODE = "patient/clinician/code"
    const val CONFIRM_CLINICIAN = "patient/clinician/confirm"

    // Verified clinician
    const val CLINICIAN_DASHBOARD = "clinician/dashboard"
    const val CLINICIAN_PATIENTS = "clinician/patients"
    const val CLINICIAN_CODE = "clinician/code"
    const val CLINICIAN_ACCOUNT = "clinician/account"
    const val CLINICIAN_REQUESTS = "clinician/requests"
    const val CLINICIAN_PATIENT = "clinician/patient/{patientId}"
    fun clinicianPatient(patientId: String) = "clinician/patient/$patientId"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", ReBandIcons.Home),
    Tab(Routes.EXERCISES, "Exercises", ReBandIcons.Exercises),
    Tab(Routes.HISTORY, "History", ReBandIcons.History),
    Tab(Routes.SETTINGS, "Settings", ReBandIcons.Settings),
)

private val clinicianTabs = listOf(
    Tab(Routes.CLINICIAN_DASHBOARD, "Dashboard", ReBandIcons.Dashboard),
    Tab(Routes.CLINICIAN_PATIENTS, "Patients", ReBandIcons.People),
    Tab(Routes.CLINICIAN_CODE, "My code", ReBandIcons.Key),
    Tab(Routes.CLINICIAN_ACCOUNT, "Account", ReBandIcons.Person),
)

/** Shows the login flow or the main app depending on the Supabase session. */
@Composable
fun AppNavHost(authViewModel: AuthViewModel = viewModel()) {
    val state by authViewModel.state.collectAsState()
    when (val current = state) {
        AuthState.Loading -> LoadingScreen()
        AuthState.SignedOut -> AuthNavHost(authViewModel)
        is AuthState.SignedIn -> SignedInRoot(current.email, authViewModel)
    }
}

/**
 * Picks patient or clinician screens from the backend-verified account type. Clinician screens
 * are shown only for a verified clinician; if the type can't be loaded (e.g. offline), the user
 * may continue with the patient experience, which grants no clinician access.
 */
@Composable
private fun SignedInRoot(email: String?, authViewModel: AuthViewModel) {
    val accountViewModel: AccountViewModel = viewModel(key = "account-$email")
    val accountState by accountViewModel.state.collectAsState()
    val busy by authViewModel.busy.collectAsState()
    val savingName by accountViewModel.savingName.collectAsState()
    val nameError by accountViewModel.nameError.collectAsState()
    val expectedRole by authViewModel.expectedRole.collectAsState()
    var continueWithoutAccount by rememberSaveable(email) { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    val onEditName = { editingName = true }

    when (val state = accountState) {
        AccountState.Loading -> LoadingScreen()
        is AccountState.Failed ->
            if (continueWithoutAccount) {
                MainNavHost(email, authViewModel, account = null, onEditName = onEditName)
            } else {
                AccountLoadErrorScreen(
                    state.message,
                    onRetry = accountViewModel::load,
                    onContinue = { continueWithoutAccount = true },
                    onLogout = authViewModel::signOut,
                )
            }
        is AccountState.Ready -> {
            // A fresh login must match the Patient/Clinician toggle; restored sessions skip this.
            val actual = if (state.account.role == AccountRole.Patient) LoginRole.Patient else LoginRole.Clinician
            when (expectedRole) {
                null -> SignedInContent(state, email, authViewModel, accountViewModel, busy, onEditName)
                actual -> {
                    LaunchedEffect(Unit) { authViewModel.confirmRole() }
                    SignedInContent(state, email, authViewModel, accountViewModel, busy, onEditName)
                }
                else -> {
                    LaunchedEffect(Unit) { authViewModel.rejectRole(actual) }
                    LoadingScreen()
                }
            }
        }
    }

    if (editingName) {
        EditNameDialog(
            current = (accountState as? AccountState.Ready)?.account?.fullName,
            saving = savingName,
            error = nameError,
            onSave = { name -> accountViewModel.saveName(name) { editingName = false } },
            onDismiss = {
                editingName = false
                accountViewModel.clearNameError()
            },
        )
    }
}

@Composable
private fun SignedInContent(
    state: AccountState.Ready,
    email: String?,
    authViewModel: AuthViewModel,
    accountViewModel: AccountViewModel,
    busy: Boolean,
    onEditName: () -> Unit,
) {
    when (state.account.role) {
        AccountRole.Patient -> MainNavHost(email, authViewModel, state.account, onEditName)
        AccountRole.VerifiedClinician -> ClinicianNavHost(state.account, authViewModel, onEditName)
        AccountRole.PendingClinician -> PendingVerificationScreen(
            state.account,
            busy = busy,
            onRefresh = accountViewModel::load,
            onEditName = onEditName,
            onLogout = authViewModel::signOut,
        )
    }
}

@Composable
private fun AuthNavHost(authViewModel: AuthViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(authViewModel, onCreateAccount = { nav.navigate(Routes.SIGNUP) })
        }
        composable(Routes.SIGNUP) {
            SignUpScreen(authViewModel, onBackToLogin = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MainNavHost(email: String?, authViewModel: AuthViewModel, account: AccountInfo?, onEditName: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val busy by authViewModel.busy.collectAsState()
    val error by authViewModel.error.collectAsState()
    // Activity-scoped, so Home, Settings and the scanner all read the same wearable state.
    val wearableViewModel: WearableViewModel = viewModel()
    val wearable by wearableViewModel.uiState.collectAsState()
    val fullScreen = currentRoute in setOf(Routes.DEVICES, Routes.SENSOR, Routes.ENTER_CLINICIAN_CODE, Routes.CONFIRM_CLINICIAN)
    // Created here, outside the NavHost, so the My Clinician, Enter Code and Confirm screens share
    // one instance. Inside a composable() route, viewModel() would be scoped to that screen alone.
    val pairing: PatientClinicianViewModel = viewModel(key = "patient-clinician-$email")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!fullScreen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Teal,
                                selectedTextColor = Teal,
                                indicatorColor = TealLight,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    email,
                    wearable = wearable,
                    onConnectWearable = { nav.navigate(Routes.DEVICES) },
                    onStartExercise = { if (wearable.canStartExercise) nav.navigate(Routes.SENSOR) },
                )
            }
            composable(Routes.EXERCISES) { ExercisesScreen() }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    email,
                    busy = busy,
                    error = error,
                    wearable = wearable,
                    fullName = account?.fullName,
                    onEditName = onEditName,
                    onOpenMyClinician = { nav.navigate(Routes.MY_CLINICIAN) },
                    onConnectWearable = { nav.navigate(Routes.DEVICES) },
                    onDisconnectWearable = wearableViewModel::disconnect,
                    onLogout = {
                        wearableViewModel.disconnect()
                        authViewModel.signOut()
                    },
                )
            }
            composable(Routes.DEVICES) { DeviceScanScreen(wearableViewModel, onBack = { nav.popBackStack() }) }
            composable(Routes.SENSOR) { BleSensorScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MY_CLINICIAN) {
                LaunchedEffect(Unit) { pairing.refresh() }
                MyClinicianScreen(
                    pairing,
                    onBack = { nav.popBackStack() },
                    onLinkClinician = {
                        pairing.startLinking()
                        nav.navigate(Routes.ENTER_CLINICIAN_CODE)
                    },
                )
            }
            composable(Routes.ENTER_CLINICIAN_CODE) {
                EnterClinicianCodeScreen(
                    pairing,
                    onBack = { nav.popBackStack() },
                    onFound = { nav.navigate(Routes.CONFIRM_CLINICIAN) },
                )
            }
            composable(Routes.CONFIRM_CLINICIAN) {
                ConfirmClinicianScreen(
                    pairing,
                    onCancel = { nav.popBackStack() },
                    onDone = { nav.popBackStack(Routes.MY_CLINICIAN, inclusive = false) },
                )
            }
        }
    }
}

/** Screens for a backend-verified clinician. Every list is re-read from the server on open. */
@Composable
private fun ClinicianNavHost(account: AccountInfo, authViewModel: AuthViewModel, onEditName: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val busy by authViewModel.busy.collectAsState()
    val clinician: ClinicianViewModel = viewModel(key = "clinician-${account.userId}")
    val openPatient = { patientId: String -> nav.navigate(Routes.clinicianPatient(patientId)) }
    val openTab = { route: String ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute != Routes.CLINICIAN_PATIENT) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    clinicianTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Teal,
                                selectedTextColor = Teal,
                                indicatorColor = TealLight,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.CLINICIAN_DASHBOARD, modifier = Modifier.padding(padding)) {
            composable(Routes.CLINICIAN_DASHBOARD) {
                LaunchedEffect(Unit) { clinician.refresh() }
                ClinicianDashboardScreen(
                    account,
                    clinician,
                    onOpenCode = { openTab(Routes.CLINICIAN_CODE) },
                    onOpenPatients = { openTab(Routes.CLINICIAN_PATIENTS) },
                    onOpenRequests = { nav.navigate(Routes.CLINICIAN_REQUESTS) },
                    onOpenPatient = openPatient,
                    onEditName = onEditName,
                )
            }
            composable(Routes.CLINICIAN_PATIENTS) {
                LaunchedEffect(Unit) { clinician.refresh() }
                ConnectedPatientsScreen(clinician, onOpenPatient = openPatient, onOpenCode = { openTab(Routes.CLINICIAN_CODE) })
            }
            composable(Routes.CLINICIAN_CODE) {
                LaunchedEffect(Unit) { clinician.refresh() }
                ClinicianCodeScreen(clinician, onBack = null)
            }
            composable(Routes.CLINICIAN_ACCOUNT) {
                ClinicianAccountScreen(account, busy = busy, onEditName = onEditName, onLogout = authViewModel::signOut)
            }
            composable(Routes.CLINICIAN_REQUESTS) {
                LaunchedEffect(Unit) { clinician.refresh() }
                ReconnectionRequestsScreen(clinician, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.CLINICIAN_PATIENT,
                arguments = listOf(navArgument("patientId") { type = NavType.StringType }),
            ) { entry ->
                PatientProgressScreen(
                    patientId = entry.arguments?.getString("patientId").orEmpty(),
                    viewModel = clinician,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
