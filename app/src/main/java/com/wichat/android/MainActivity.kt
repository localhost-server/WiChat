package com.wichat.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.wichat.android.wifi.WifiMeshService
import com.wichat.android.wifi.WifiMeshServiceManager
import com.wichat.android.services.BitchatBackgroundService
import com.wichat.android.onboarding.WifiCheckScreen
import com.wichat.android.onboarding.WifiStatus
import com.wichat.android.onboarding.WifiStatusManager
import com.wichat.android.onboarding.BatteryOptimizationManager
import com.wichat.android.onboarding.BatteryOptimizationScreen
import com.wichat.android.onboarding.BatteryOptimizationStatus
import com.wichat.android.onboarding.InitializationErrorScreen
import com.wichat.android.onboarding.InitializingScreen
import com.wichat.android.onboarding.LocationCheckScreen
import com.wichat.android.onboarding.LocationStatus
import com.wichat.android.onboarding.LocationStatusManager
import com.wichat.android.onboarding.OnboardingCoordinator
import com.wichat.android.onboarding.OnboardingState
import com.wichat.android.onboarding.PermissionExplanationScreen
import com.wichat.android.onboarding.PermissionManager
import com.wichat.android.ui.ChatScreen
import com.wichat.android.ui.ChatViewModel
import com.wichat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var permissionManager: PermissionManager
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private lateinit var wifiStatusManager: WifiStatusManager
    private lateinit var locationStatusManager: LocationStatusManager
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager

    private lateinit var meshService: WifiMeshService
    private val mainViewModel: MainViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(application, meshService) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use shared service container for memory efficiency
        val serviceContainer = com.wichat.android.core.BitchatServiceContainer.getInstance(this)
        meshService = serviceContainer.getMeshService()
        val meshServiceManager = serviceContainer.getMeshServiceManager()
        meshServiceManager.addDelegate(chatViewModel)
        
        Log.d("MainActivity", "📊 ${serviceContainer.getMemoryStats()}")
        
        // WORKAROUND: Force peer list update every 5 seconds to fix UI refresh issue
        lifecycleScope.launch {
            while (true) {
                delay(5000) // Every 5 seconds
                try {
                    // Get peer nicknames (this gives us connected peers) and force UI update
                    val peerNicknames = meshService.getPeerNicknames()
                    val currentPeers = peerNicknames.keys.toList()
                    chatViewModel.didUpdatePeerList(currentPeers)
                    Log.d("MainActivity", "Forced peer list update: ${currentPeers.size} peers - $currentPeers")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Error in forced peer list update: ${e.message}")
                }
            }
        }

        permissionManager = PermissionManager(this)
        wifiStatusManager = WifiStatusManager(
            activity = this,
            context = this,
            onWifiEnabled = ::handleWifiEnabled,
            onWifiDisabled = ::handleWifiDisabled
        )
        locationStatusManager = LocationStatusManager(
            activity = this,
            context = this,
            onLocationEnabled = ::handleLocationEnabled,
            onLocationDisabled = ::handleLocationDisabled
        )
        batteryOptimizationManager = BatteryOptimizationManager(
            activity = this,
            context = this,
            onBatteryOptimizationDisabled = ::handleBatteryOptimizationDisabled,
            onBatteryOptimizationFailed = ::handleBatteryOptimizationFailed
        )
        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager,
            onOnboardingComplete = ::handleOnboardingComplete,
            onOnboardingFailed = ::handleOnboardingFailed
        )

        setContent {
            BitchatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingFlowScreen()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.onboardingState.collect { state ->
                    handleOnboardingStateChange(state)
                }
            }
        }

        if (mainViewModel.onboardingState.value == OnboardingState.CHECKING) {
            checkOnboardingStatus()
        }
    }

    @Composable
    private fun OnboardingFlowScreen() {
        val onboardingState by mainViewModel.onboardingState.collectAsState()
        val wifiStatus by mainViewModel.wifiStatus.collectAsState()
        val locationStatus by mainViewModel.locationStatus.collectAsState()
        val batteryOptimizationStatus by mainViewModel.batteryOptimizationStatus.collectAsState()
        val errorMessage by mainViewModel.errorMessage.collectAsState()
        val isWifiLoading by mainViewModel.isWifiLoading.collectAsState()
        val isLocationLoading by mainViewModel.isLocationLoading.collectAsState()
        val isBatteryOptimizationLoading by mainViewModel.isBatteryOptimizationLoading.collectAsState()

        when (onboardingState) {
            OnboardingState.CHECKING -> InitializingScreen()
            OnboardingState.WIFI_CHECK -> {
                WifiCheckScreen(
                    status = wifiStatus,
                    onEnableWifi = {
                        mainViewModel.updateWifiLoading(true)
                        wifiStatusManager.requestEnableWifi()
                    },
                    onRetry = { checkWifiAndProceed() },
                    isLoading = isWifiLoading
                )
            }
            OnboardingState.LOCATION_CHECK -> {
                LocationCheckScreen(
                    status = locationStatus,
                    onEnableLocation = {
                        mainViewModel.updateLocationLoading(true)
                        locationStatusManager.requestEnableLocation()
                    },
                    onRetry = { checkLocationAndProceed() },
                    isLoading = isLocationLoading
                )
            }
            OnboardingState.BATTERY_OPTIMIZATION_CHECK -> {
                BatteryOptimizationScreen(
                    status = batteryOptimizationStatus,
                    onDisableBatteryOptimization = {
                        mainViewModel.updateBatteryOptimizationLoading(true)
                        batteryOptimizationManager.requestDisableBatteryOptimization()
                    },
                    onRetry = { checkBatteryOptimizationAndProceed() },
                    onSkip = { proceedWithPermissionCheck() },
                    isLoading = isBatteryOptimizationLoading
                )
            }
            OnboardingState.PERMISSION_EXPLANATION -> {
                PermissionExplanationScreen(
                    permissionCategories = permissionManager.getCategorizedPermissions(),
                    onContinue = {
                        mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
                        onboardingCoordinator.requestPermissions()
                    }
                )
            }
            OnboardingState.PERMISSION_REQUESTING -> InitializingScreen()
            OnboardingState.INITIALIZING -> InitializingScreen()
            OnboardingState.COMPLETE -> {
                val backCallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val handled = chatViewModel.handleBackPressed()
                        if (!handled) {
                            this.isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            this.isEnabled = true
                        }
                    }
                }
                onBackPressedDispatcher.addCallback(this, backCallback)
                ChatScreen(viewModel = chatViewModel)
            }
            OnboardingState.ERROR -> {
                InitializationErrorScreen(
                    errorMessage = errorMessage,
                    onRetry = {
                        mainViewModel.updateOnboardingState(OnboardingState.CHECKING)
                        checkOnboardingStatus()
                    },
                    onOpenSettings = { onboardingCoordinator.openAppSettings() }
                )
            }
        }
    }

    private fun handleOnboardingStateChange(state: OnboardingState) {
        when (state) {
            OnboardingState.COMPLETE -> Log.d("MainActivity", "Onboarding completed - app ready")
            OnboardingState.ERROR -> Log.e("MainActivity", "Onboarding error state reached")
            else -> {}
        }
    }

    private fun checkOnboardingStatus() {
        Log.d("MainActivity", "Checking onboarding status")
        lifecycleScope.launch {
            delay(500)
            checkWifiAndProceed()
        }
    }

    private fun checkWifiAndProceed() {
        if (permissionManager.isFirstTimeLaunch()) {
            Log.d("MainActivity", "First-time launch, skipping Wifi check")
            proceedWithPermissionCheck()
            return
        }
        mainViewModel.updateWifiStatus(wifiStatusManager.checkWifiStatus())
        when (mainViewModel.wifiStatus.value) {
            WifiStatus.ENABLED -> checkLocationAndProceed()
            WifiStatus.DISABLED -> {
                mainViewModel.updateOnboardingState(OnboardingState.WIFI_CHECK)
                mainViewModel.updateWifiLoading(false)
            }
            WifiStatus.NOT_SUPPORTED -> {
                mainViewModel.updateOnboardingState(OnboardingState.WIFI_CHECK)
                mainViewModel.updateWifiLoading(false)
            }
        }
    }

    private fun proceedWithPermissionCheck() {
        Log.d("MainActivity", "Proceeding with permission check")
        lifecycleScope.launch {
            delay(200)
            if (permissionManager.isFirstTimeLaunch()) {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (permissionManager.areAllPermissionsGranted()) {
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            } else {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
        }
    }

    private fun handleWifiEnabled() {
        Log.d("MainActivity", "Wifi enabled by user")
        mainViewModel.updateWifiLoading(false)
        mainViewModel.updateWifiStatus(WifiStatus.ENABLED)
        checkLocationAndProceed()
    }

    private fun checkLocationAndProceed() {
        if (permissionManager.isFirstTimeLaunch()) {
            Log.d("MainActivity", "First-time launch, skipping location check")
            proceedWithPermissionCheck()
            return
        }
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())
        when (mainViewModel.locationStatus.value) {
            LocationStatus.ENABLED -> checkBatteryOptimizationAndProceed()
            LocationStatus.DISABLED -> {
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            LocationStatus.NOT_AVAILABLE -> {
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }

    private fun handleLocationEnabled() {
        Log.d("MainActivity", "Location services enabled by user")
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(LocationStatus.ENABLED)
        checkBatteryOptimizationAndProceed()
    }

    private fun handleLocationDisabled(message: String) {
        Log.w("MainActivity", "Location services disabled or failed: $message")
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())
        when {
            mainViewModel.locationStatus.value == LocationStatus.NOT_AVAILABLE -> {
                mainViewModel.updateErrorMessage(message)
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
            }
            else -> mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
        }
    }

    private fun handleWifiDisabled(message: String) {
        Log.w("MainActivity", "Wifi disabled or failed: $message")
        mainViewModel.updateWifiLoading(false)
        mainViewModel.updateWifiStatus(wifiStatusManager.checkWifiStatus())
        when {
            mainViewModel.wifiStatus.value == WifiStatus.NOT_SUPPORTED -> {
                mainViewModel.updateErrorMessage(message)
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
            }
            message.contains("Permission") && permissionManager.isFirstTimeLaunch() -> {
                proceedWithPermissionCheck()
            }
            message.contains("Permission") -> {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
            else -> mainViewModel.updateOnboardingState(OnboardingState.WIFI_CHECK)
        }
    }

    private fun handleOnboardingComplete() {
        val currentWifiStatus = wifiStatusManager.checkWifiStatus()
        val currentLocationStatus = locationStatusManager.checkLocationStatus()
        val currentBatteryOptimizationStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }

        when {
            currentWifiStatus != WifiStatus.ENABLED -> {
                mainViewModel.updateWifiStatus(currentWifiStatus)
                mainViewModel.updateOnboardingState(OnboardingState.WIFI_CHECK)
                mainViewModel.updateWifiLoading(false)
            }
            currentLocationStatus != LocationStatus.ENABLED -> {
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            currentBatteryOptimizationStatus == BatteryOptimizationStatus.ENABLED -> {
                mainViewModel.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                mainViewModel.updateBatteryOptimizationLoading(false)
            }
            else -> {
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            }
        }
    }

    private fun handleOnboardingFailed(message: String) {
        Log.e("MainActivity", "Onboarding failed: $message")
        mainViewModel.updateErrorMessage(message)
        mainViewModel.updateOnboardingState(OnboardingState.ERROR)
    }

    private fun checkBatteryOptimizationAndProceed() {
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }
        val currentBatteryOptimizationStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        mainViewModel.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
        when (currentBatteryOptimizationStatus) {
            BatteryOptimizationStatus.DISABLED, BatteryOptimizationStatus.NOT_SUPPORTED -> proceedWithPermissionCheck()
            BatteryOptimizationStatus.ENABLED -> {
                mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                mainViewModel.updateBatteryOptimizationLoading(false)
            }
        }
    }

    private fun handleBatteryOptimizationDisabled() {
        mainViewModel.updateBatteryOptimizationLoading(false)
        mainViewModel.updateBatteryOptimizationStatus(BatteryOptimizationStatus.DISABLED)
        proceedWithPermissionCheck()
    }

    private fun handleBatteryOptimizationFailed(message: String) {
        mainViewModel.updateBatteryOptimizationLoading(false)
        val currentStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        mainViewModel.updateBatteryOptimizationStatus(currentStatus)
        mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
    }

    private fun initializeApp() {
        Log.d("MainActivity", "Starting app initialization")
        lifecycleScope.launch {
            try {
                delay(1000)
                if (!permissionManager.areAllPermissionsGranted()) {
                    val missing = permissionManager.getMissingPermissions()
                    handleOnboardingFailed("Some permissions were revoked. Please grant all permissions to continue.")
                    return@launch
                }
                // Start background service to maintain mesh networking when app is backgrounded
                BitchatBackgroundService.startService(this@MainActivity)
                Log.d("MainActivity", "Background service started for mesh networking")
                
                // Also ensure mesh service is started immediately for UI interaction
                val meshServiceManager = WifiMeshServiceManager.getInstance(this@MainActivity)
                meshServiceManager.startServices()
                handleNotificationIntent(intent)
                delay(500)
                Log.d("MainActivity", "App initialization complete")
                mainViewModel.updateOnboardingState(OnboardingState.COMPLETE)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to initialize app", e)
                handleOnboardingFailed("Failed to initialize the app: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            handleNotificationIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            meshService.connectionManager.setAppBackgroundState(false)
            chatViewModel.setAppBackgroundState(false)
            val currentWifiStatus = wifiStatusManager.checkWifiStatus()
            if (currentWifiStatus != WifiStatus.ENABLED) {
                mainViewModel.updateWifiStatus(currentWifiStatus)
                mainViewModel.updateOnboardingState(OnboardingState.WIFI_CHECK)
                mainViewModel.updateWifiLoading(false)
                return
            }
            val currentLocationStatus = locationStatusManager.checkLocationStatus()
            if (currentLocationStatus != LocationStatus.ENABLED) {
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            meshService.connectionManager.setAppBackgroundState(true)
            chatViewModel.setAppBackgroundState(true)
        }
    }

    private fun handleNotificationIntent(intent: Intent) {
        val shouldOpenPrivateChat = intent.getBooleanExtra(
            com.wichat.android.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT,
            false
        )
        if (shouldOpenPrivateChat) {
            val peerID = intent.getStringExtra(com.wichat.android.ui.NotificationManager.EXTRA_PEER_ID)
            val senderNickname = intent.getStringExtra(com.wichat.android.ui.NotificationManager.EXTRA_SENDER_NICKNAME)
            if (peerID != null) {
                Log.d("MainActivity", "Opening private chat with $senderNickname (peerID: $peerID) from notification")
                chatViewModel.startPrivateChat(peerID)
                chatViewModel.clearNotificationsForSender(peerID)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationStatusManager.cleanup()
        } catch (e: Exception) {
            Log.w("MainActivity", "Error cleaning up location status manager: ${e.message}")
        }
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Don't stop mesh services - let background service handle them
            // Background service will continue running for offline messaging
            Log.d("MainActivity", "Activity destroyed - background service continues running")
        }
    }
}