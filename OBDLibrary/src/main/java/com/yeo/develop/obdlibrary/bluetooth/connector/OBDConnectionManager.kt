package com.yeo.develop.obdlibrary.bluetooth.connector

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.yeo.develop.obdlibrary.bluetooth.connectionstate.BluetoothConnectionState
import com.yeo.develop.obdlibrary.bluetooth.extensions.whileIndexedWithSuspendAction
import com.yeo.develop.obdlibrary.calculator.SpeedDistanceCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.*

/**
 * 블루투스 소켓 통신을 이용해 OBD와의 통신을 진행하는 클래스입니다
 * Android 12 이상부터 BLUETOOTH_ADMIN 권한과 BLUETOOTH_SCAN 권한을 필요로합니다.
 * @author yeo_develop
 * */
object OBDConnectionManager {
    private const val UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB"

    /**
     * context 의존적이라 leak이 발생할 수 있으므로 WeakReference를 활용 해줍시다.
     * **/
    private lateinit var bluetoothAdapter: WeakReference<BluetoothAdapter>

    private var socket: BluetoothSocket? = null

    private val inputStream: InputStream?
        get() = socket?.inputStream

    private val outputStream: OutputStream?
        get() = socket?.outputStream

    private val buffer = ByteArray(1024)

    private val initializeCommand = listOf<String>(
        "ATZ",   // 장치 초기화 명령입니다. 최초 연결 시 1회 호출이 권장됩니다.

        //아래는 부가 세팅입니다. 기본값은 0으로 설정 해놓았으나, 추가 정보를 원할 시 1을 선택해주세요.
        // ex) ATE0 -> ATE1

        "ATH0",  // 헤더 끄기 (선택 사항)
        // 활성화 시 응답 데이터의 앞부분에 헤더 정보가 추가됩니다.
        // 활성화 시 : 7E803410DXX
        // 비활성화 시 : 410DXX

        "ATL0",  // 줄 바꿈 끄기 (선택 사항)
        // 활성화 시 response 수신 시 개행문자가 추가됩니다
        // 활성화 시 : 010D\n410DXX
        // 비활성화 시 : 410DXX

        "ATS0",  // 공백 끄기 (선택 사항)
        // 활성화 시 response 수신 시 공백이 추가됩니다.
        // 활성화 시 : 41 0D XX
        // 비활성화 시 : 410DXX

        "ATE0",  // 에코
        //활성 화 시 자신이 보낸 명령을 response의 앞부분에 추가하여 내려줍니다.
        // 활성화 시 : 010D410DXX
        // 비활성화 시 : 410DXX
    )

    private var isInitialized = false
    private var recentConnectedMacAddress: String? = null

    private val _bluetoothConnectionState = MutableStateFlow(BluetoothConnectionState.DISCONNECT)
    val bluetoothConnectionState = _bluetoothConnectionState.asStateFlow()

    private var onConnectJob: Job? = null

    /**
     * bluetoothAdapter 를 사용하기 위해선 context를 요구하기에, context 를 인입하는 과정을 거칩니다.
     * */
    fun init(context: Context) {
        // WeakReference를 사용해 혹시모를 leak을 방지해주도록 합시다.
        bluetoothAdapter =
            WeakReference<BluetoothAdapter>((context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter)

    }

    /**
     * OBD와의 연결을 시도합니다.
     * @param macAddress OBD 장치의 mac 주소 값입니다. 무작위 영어/숫자로 구성된 XX:XX:XX:XX:XX:XX 형식이 되어야 정상적으로 연결이 될겁니다.
     * Android 12 이상부터 BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADMIN 권한을 추가로 받아야합니다.
     * */
    @Suppress("MissingPermission")
    fun connect(macAddress: String) {
        if (bluetoothConnectionState.value == BluetoothConnectionState.TRY) {
            return
        }
        _bluetoothConnectionState.value = BluetoothConnectionState.TRY
        recentConnectedMacAddress = macAddress
        CoroutineScope(Dispatchers.Default).launch {
            val remoteDevice =
                bluetoothAdapter.get()?.getRemoteDevice(macAddress)
            kotlin.runCatching {
                var temporarySocket: BluetoothSocket? = null
                //index는 굳이 안넣어도 되지만.. 재 시도 횟수 카운팅 겸 디버그 용으로 좋을 듯 해서 남깁니다.
                whileIndexedWithSuspendAction(stopCondition = { temporarySocket?.isConnected == true }) { times ->
                    Timber.d("TRY TO RECONNECT COUNT : $times")
                    // 1초에 한번 "될때까지" 재연결을 무한정 시도합니다.
                    withTimeout(1000L) {
                        runCatching {
                            temporarySocket = remoteDevice?.createRfcommSocketToServiceRecord(
                                UUID.fromString(UUID_STRING)
                            )?.apply { connect() }
                        }.onFailure {
                            return@withTimeout
                        }.onSuccess {
                            Timber.d("Socket created and connected successfully.")
                        }
                    }
                }
                //IOT 디바이스에서 이루어지는 비동기 작업이므로 연결후 task가 원활하게 이루어질 수 있도록 딜레이를 주도록 합시다. 4초.
                delay(4000L)
                temporarySocket
            }.onSuccess { connectedSocket ->
                Timber.d("Socket created and connected successfully.")
                socket = connectedSocket
                onConnect()
                cancel()
            }.onFailure {
                Timber.e("Failed to create or connect the socket: $it")
            }
        }
    }

    /**
     * 연결된 OBD와의 연결을 끊습니다.
     * */
    fun disconnect() {
        recentConnectedMacAddress = null
        _bluetoothConnectionState.value = BluetoothConnectionState.DISCONNECT
        isInitialized = false
        socket?.close()
        socket = null
    }

    /****
     * OBD 디바이스로 데이터를 송신합니다.
     */
    private fun sendData(data: String) {
        kotlin.runCatching {
            outputStream?.let { output ->
                Timber.d("SEND DATA -> $data , ${data.toByteArray()}")
                output.write(data.toByteArray())
                output.flush()
            } ?: throw NullPointerException("Connection not ready")
        }.onSuccess {
            Timber.d("SEND DATA SUCCESS")
        }.onFailure { t ->
            //소켓이 존재하는데 NullpointerException을 제외한 무언가 throw 되는것은 연결이 끊어진 것으로 판단합니다.
            handleExceptionWhileCommunicate(t)
        }
    }

    /**
     * OBD로부터 받아온 response 010DXX << 값을 핸들링해 SpeedDistanceCalculator로 넘겨버리는 함수입니다. (sendData 에서 추가하면 다른값 으로도 넘어옵니다! 다른 정보가 필요하면 [sendData] 함수를 참고 해주세요.)
     * */
    private fun receiveData(): String? {
        return kotlin.runCatching {
            inputStream?.let { input ->
                val bytes = input.read(buffer)
                Timber.d("buffer size -> ${buffer.size}")
                val data = String(buffer, 0, bytes)
                when {  //지금은 속도값에 해당되는 401D만 핸들링 하지만 추후 어떤 값을 추가할지 모르기 때문에 when으로 작성해놓습니다.
                    data.contains("410D") -> {
                        Timber.tag("RAWDATA").d("Speed Data Received : $data")
                        //410DXX 의 값을 뽑아 거리계산 오다를 내립니다.
                        val decimalSpeed =
                            data.filter { it.isLetterOrDigit() }.takeLast(2).toInt(16)
                        Timber.tag("RAWDATA").d("Speed Data to decimal : $decimalSpeed")
                        SpeedDistanceCalculator.updateSpeed(decimalSpeed)
                    }
                }
                data
            } ?: throw NullPointerException("Connection Not Ready")
        }.onSuccess { res ->
            Timber.d("RECEIVING DATA SUCCESS -> ${res.trim()}")
            res.trim()
        }.onFailure { t ->
            // outputStream과 마찬가지로소켓이 존재하는데 NullpointerException을 제외한 무언가 throw 되는것은 연결이 끊어진 것으로 판단합니다.
            handleExceptionWhileCommunicate(t)
        }.getOrNull()
    }

    /**
     * OBD 기기가 연결/재연결 되었을때 호출됩니다. 200ms 간격으로 request를 보내고, 매 연결시마다 initialize command를 보냅니다.
     * */
    private fun onConnect() {
        onConnectJob = CoroutineScope(Dispatchers.Default).launch {
            while (currentCoroutineContext().isActive) {
                delay(200L)
                Timber.d("SOCKET CONNECTION STATE -> ${socket?.isConnected}")
                when ((socket?.isConnected ?: false)) {
                    true -> {
                        if (!isInitialized) {
                            initialize()
                        }
                        requestSpeed()
                    }
                    false -> {
                        Timber.d("CONNECTION NOT READY")
                    }
                }
            }
        }
        onConnectJob?.start()
    }

    /**
     * 연결이 끊겼을때 기존 소켓을 비우고 재연결을 시도해주는 함수입니다.
     * */
    private fun onConnectionLost() {
        onConnectJob?.cancel("job canceled cause of connection lost")
        onConnectJob = null
        recentConnectedMacAddress?.let { macAddress ->
            isInitialized = false
            socket?.close()
            socket = null
            connect(macAddress)
        } ?: disconnect()
    }

    /**
     * 매 OBD 연결 시 장치를 초기화해주는 함수입니다. 커맨드의 목록은 상술한 initializeCommand를 참고하세요.
     * */
    private suspend fun initialize() {
        initializeCommand.forEach { command ->
            sendData(command + "\r")
            delay(500L)
            receiveData()
        }
        isInitialized = true
        _bluetoothConnectionState.value = BluetoothConnectionState.CONNECT
    }

    /**
     * 연결후 initialize가 완료되면, 속도값을 요청하게됩니다.
     * 속도값에 대응하는 010D 만 보내고있지만, 다른 OBD PID number도 대응이 되니 자유롭게 함수를 구성하셔서 사용하실 수 있습니다.
     * 대응되는 값에 대해선 하단의 링크를 참고해주세요.
     * https://en.wikipedia.org/wiki/OBD-II_PIDs
     * */
    private suspend fun requestSpeed() {
        //010D\r - 현재 자동차가 주행하고있는 속도 정보를 요청합니다.
        sendData("010D\r")
        delay(200L)
        receiveData()
    }

    private fun handleExceptionWhileCommunicate(exception: Throwable) {
        when(exception) {
            is NullPointerException -> {
                Timber.d("Connection Not Ready!")
            }
            else -> {
                Timber.d("connection lost!")
                onConnectionLost()
            }
        }
    }
}