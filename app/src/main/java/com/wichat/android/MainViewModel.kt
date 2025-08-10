package com.wichat.android

import androidx.lifecycle.ViewModel
import com.wichat.android.onboarding.WifiStatus
import com.wichat.android.onboarding.LocationStatus
import com.wichat.android.onboarding.OnboardingState
import com.wichat.android.onboarding.BatteryOptimizationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {

    private val _onboardingState = MutableStateFlow(OnboardingState.CHECKING)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState.asStateFlow()

    private val _wifiStatus = MutableStateFlow(WifiStatus.ENABLED)
    val wifiStatus: StateFlow<WifiStatus> = _wifiStatus.asStateFlow()

    private val _locationStatus = MutableStateFlow(LocationStatus.ENABLED)
    val locationStatus: StateFlow<LocationStatus> = _locationStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    private val _isWifiLoading = MutableStateFlow(false)
    val isWifiLoading: StateFlow<Boolean> = _isWifiLoading.asStateFlow()

    private val _isLocationLoading = MutableStateFlow(false)
    val isLocationLoading: StateFlow<Boolean> = _isLocationLoading.asStateFlow()

    private val _batteryOptimizationStatus = MutableStateFlow(BatteryOptimizationStatus.ENABLED)
    val batteryOptimizationStatus: StateFlow<BatteryOptimizationStatus> = _batteryOptimizationStatus.asStateFlow()

    private val _isBatteryOptimizationLoading = MutableStateFlow(false)
    val isBatteryOptimizationLoading: StateFlow<Boolean> = _isBatteryOptimizationLoading.asStateFlow()

    fun updateOnboardingState(state: OnboardingState) {
        _onboardingState.value = state
    }

    fun updateWifiStatus(status: WifiStatus) {
        _wifiStatus.value = status
    }

    fun updateLocationStatus(status: LocationStatus) {
        _locationStatus.value = status
    }

    fun updateErrorMessage(message: String) {
        _errorMessage.value = message
    }

    fun updateWifiLoading(loading: Boolean) {
        _isWifiLoading.value = loading
    }

    fun updateLocationLoading(loading: Boolean) {
        _isLocationLoading.value = loading
    }

    fun updateBatteryOptimizationStatus(status: BatteryOptimizationStatus) {
        _batteryOptimizationStatus.value = status
    }

    fun updateBatteryOptimizationLoading(loading: Boolean) {
        _isBatteryOptimizationLoading.value = loading
    }
}