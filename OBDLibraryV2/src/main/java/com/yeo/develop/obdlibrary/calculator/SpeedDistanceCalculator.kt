package com.yeo.develop.obdlibrary.calculator



import com.yeo.develop.obdlibrary.bluetooth.connectionstate.BluetoothConnectionState
import com.yeo.develop.obdlibrary.bluetooth.connector.OBDConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit


/**
 * OBD-II에서 넘겨주는 속력을 이용해 거리를 적산 / 계산하는 기능을 가진 매니저 오브젝트.
 * 로직에 문제가 있을수도 있습니다!
 * @author yeo-develop
 */
object SpeedDistanceCalculator {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private var isIgnoreUpdate = false
    private var isConnected = false
    // 이전 적산에 사용된 속도
    private val lastSpeed = MutableStateFlow<Int>(0)

    // 적산된 거리 (미터 단위), 마지막 적산 진행 시각
    private val _distance = MutableStateFlow<Double>(0.0)
    val distance = _distance.asStateFlow()

    // !!보정된!! 적산된 거리 (미터 단위), 마지막 적산 진행 시각
    private val _distanceCorrected = MutableStateFlow<Int>(0)
    val distanceCorrected = _distanceCorrected.asStateFlow()

    private val _speedLastUpdatedTime = MutableStateFlow(LocalDateTime.now())
    val speedLastUpdatedTime = _speedLastUpdatedTime.asStateFlow()


    private val _currentSpeedFlow = MutableSharedFlow<Int>()
    val currentSpeedFlow = _currentSpeedFlow.asSharedFlow()

    //val speedFromDistanceManager = hardwareManager.currentSpeedFlow

    private var distanceCalculationJob: Job? = null

    init {
        /****
         * 연결됨 상태를 제외한 다른 state에선 거리를 쌓도록 두면 안됩니다.
         * SpeedDistanceCalculator 스펙 상 최근에 거리가 들어온 시간과 거리 인입 시점의 deltaTime을 기준으로 거리 계산을 하기 때문입니다.
         * 만약 끊겼을때 거리적산을 중지하지 않는다면 거리가 와장창 튀어서 큰일날수도 있어요.
         * 라고하지만 이미 coerceAtMost로 0.8초 제한을 두고있어서 무의미할수도.
         */
        CoroutineScope(Dispatchers.Default).launch {
            OBDConnectionManager.bluetoothConnectionState.collectLatest { connectState ->
                when(connectState){
                    BluetoothConnectionState.TRY,
                    BluetoothConnectionState.DISCONNECT -> {
                        onObdDisconnected()
                    }
                    else -> {
                        onObdConnected()
                    }
                }
            }
        }
    }

    /**
     * 속도(와 속도 계산 시각)을 리셋합니다.
     */
    fun resetDistance() {
        lastSpeed.value = 0
        _distance.value = 0.0
        _distanceCorrected.value = 0
        _speedLastUpdatedTime.value = LocalDateTime.now()
    }

    /**
     * 속도 계산이 진행되는 코루틴을 실행시킵니다.
     */
    fun beginDistanceCalculation() {
        distanceCalculationJob?.cancel()
        distanceCalculationJob = coroutineScope.launch {
            currentSpeedFlow.collect { speedKph -> // km/h단위.
                if(!isIgnoreUpdate || !isConnected) {
                    val lastSpeedKph = lastSpeed.value
                    // 마지막 갱신으로부터 지난 시간, 초단위
                    val deltaTime = (_speedLastUpdatedTime.value.until(
                        LocalDateTime.now(),
                        ChronoUnit.MILLIS
                    ) * 0.001).coerceAtMost(0.8)
                    // 주행 속도 (km/h) x 미터 변환 (10/36 = 1/3.6) x 마지막 갱신으로부터 지난 시간 델타 (seconds)
                    // = 현재 속도 (m/s) x 시간 = 거리 델타 (m)
                    val deltaDistPrevious = (lastSpeedKph / 3.6) * deltaTime
                    val deltaDistNow = (speedKph / 3.6) * deltaTime

                    // 최종 이동 거리는 속도-시간 그래프의 면적이기 때문에, 속도가 갑자기 100-200 요렇게 끊겨서 올라가는게 아닌 스무스하게 이어지는 형태를 고려해야합니다.
                    // 따라서 이전에 조회된 속도로부터 등가속(?)해서 현재 속도로 이동했음을 가정하면, 실제 거리 델타는 <사각형> + <삼각형> 형태가 될 것입니다.
                    // 사각형의 면적을 가지는 이전에 조회된 속도로 계산한 거리 델타 + 이전-현재 속도를 고려해 삼각형의 면적을 가지는 거리 델타를 더하면 오차가 그나마 줄어들지 않을까 싶어요.
                    val deltaDistCorrected =
                        deltaDistPrevious + ((deltaDistNow - deltaDistPrevious) * 0.5)

                    _distance.value += deltaDistNow
                    _distanceCorrected.value += deltaDistCorrected.toInt()
                    lastSpeed.value = speedKph
                    Timber.e("hardwareManager.currentSpeedFlow: $speedKph | deltaTime: $deltaTime | deltaDist: $deltaDistNow | deltaDistCorrected: $deltaDistCorrected >> _distance: ${_distance.value} (_distanceCorrected: ${_distanceCorrected.value})")
                    _speedLastUpdatedTime.value = LocalDateTime.now()
                }else {
                    _speedLastUpdatedTime.value = LocalDateTime.now()
                }
            }
        }
    }
    /**
     * 속도 계산이 진행되는 코루틴을 중지합니다.
     * 주행 종료 및 앱 종료시에 호출해주세요.
     */
    fun endDistanceCalculation() {
        resetDistance()
        distanceCalculationJob?.cancel()
        distanceCalculationJob = null
    }

    /**DistanceManager의 속력을 설정합니다.*/
    fun updateSpeed(speed: Int) = coroutineScope.launch {
        _currentSpeedFlow.emit(speed)
    }

    fun pause() {
        isIgnoreUpdate = true
    }

    fun resume() {
        isIgnoreUpdate = false
    }

    private fun onObdConnected() {
        isConnected = true
    }

    private fun onObdDisconnected() {
        isConnected = false
    }

}

