package com.tunnelac.relay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WWC2 헤드유닛의 CAN 서비스(com.wwc2.canbussdk.MainService)와 직접 통신하는 클라이언트.
 * 순정 런처(com.wwc2.mainapp)가 쓰는 것과 같은 AIDL(ICanbusDriver / ICanbusCallback)을
 * AIDL 파일 없이 Binder transact 로 직접 구현했다(트랜잭션 번호는 순정 APK 에서 확인한 값).
 *
 * 전송  : ICanbusDriver.sendCanbusCmdToSDK(module, 4, bundle)   [트랜잭션 13]
 *         → CarSettingsCanBusLogic / "CAR_STATE_ENABLE" 배열 / cmd = 프레임 타입(공조 0x84)
 *         → 데이터 [키코드, 1](누름), 200ms 뒤 [키코드, 0](뗌)  (순정 런처 FragmentCarEvents.sendAir 와 동일)
 * 수신  : ICanbusDriver.registerCallback(cb) [16] 로 변경 통지를 받고,
 *         getAirconditionData() [3] 의 Bundle 에서 "IN_OUT_LOOP"(boolean, true=내기) 를 조회한다.
 */
object WwcCan {
    const val PKG = "com.wwc2.canbussdk"
    private const val SVC_CLASS = "com.wwc2.canbussdk.MainService"
    private const val SVC_ACTION = "com.wwc2.canbussdk.MainService"

    private const val DRIVER_DESC = "com.wwc2.canbussdk.ICanbusDriver"
    private const val CB_DESC = "com.wwc2.canbussdk.ICanbusCallback"

    // ICanbusDriver 트랜잭션 번호
    private const val TX_GET_AIR = 3
    private const val TX_SEND_CMD = 13
    private const val TX_REGISTER = 16
    private const val TX_UNREGISTER = 17

    // ICanbusCallback 트랜잭션 번호
    private const val CB_SEND_DATA_TO_CAN = 1
    private const val CB_CAR_STEER = 2
    private const val CB_CAN_VERSION = 3
    private const val CB_CAN_CONNECT = 4
    private const val CB_DATA = 5
    private const val CB_SUPPORT_FUNC = 6
    private const val CB_SOUND_CHANNEL = 7
    private const val CB_CAN_SERIES = 8

    private const val AIR_MODULE = "com.wwc2.main.canbus.aircondition.AirConditionCanBusLogic"
    private const val SETTINGS_MODULE = "com.wwc2.main.canbus.carsettings.CarSettingsCanBusLogic"
    private const val KEY_LOOP = "IN_OUT_LOOP"
    private const val ITEM_NAME = "CAR_STATE_ENABLE"
    private const val PARAM_ARRAY = 4          // sendCanbusCmdToSDK 의 두 번째 인자(배열 파라미터)
    private const val BIND_RETRY_MS = 5000L

    /** 공조 제어 프레임 타입(0x84 = AIR_CTL) */
    const val FRAME_AIR = 0x84
    const val RELEASE_DELAY_MS = 200L

    /** 순환 모드 값(폰 앱의 모드 코드와 같은 번호) */
    const val MODE_INNER = 1
    const val MODE_OUTER = 2
    const val MODE_AUTO = 3
    /** 내기/외기/오토를 순환하는 키(0x13). 한 번 누를 때마다 다음 모드로 넘어간다. */
    const val KEY_RECIRC = 0x13
    private const val KEY_AQS = "AQS_INNERLOOP"
    private const val VERIFY_DELAY_MS = 700L
    private const val MAX_PRESSES = 3

    private val lock = Any()
    @Volatile private var driver: IBinder? = null
    private var conn: ServiceConnection? = null
    private var bindStartedAt = 0L
    @Volatile private var registered = false
    @Volatile private var dumped = false

    /** 차량 내기순환 상태(true=내기). 모르면 null. 서비스 연결이 끊기면 null 로 되돌린다. */
    @Volatile var innerLoop: Boolean? = null
        private set

    /** AQS_INNERLOOP: 순환 "오토" 여부(실측: 오토일 때 IN_OUT_LOOP=1, AQS_INNERLOOP=1). 모르면 null. */
    @Volatile var aqsLoop: Boolean? = null
        private set

    /**
     * 차량의 순환 모드. 1=내기(loop=1, aqs=0), 2=외기(loop=0), 3=오토(loop=1, aqs=1). 모르면 null.
     * aqs 값을 받은 적이 없으면 loop=1 은 내기로 본다.
     */
    val recircMode: Int?
        get() {
            val l = innerLoop ?: return null
            return if (!l) MODE_OUTER else if (aqsLoop == true) MODE_AUTO else MODE_INNER
        }

    fun modeName(m: Int?): String = when (m) {
        MODE_INNER -> "내기"; MODE_OUTER -> "외기"; MODE_AUTO -> "오토"; else -> "알 수 없음"
    }

    @Volatile var log: (String) -> Unit = {}

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wwc-can").apply { isDaemon = true }
    }

    /** 서비스에 연결돼 있으면 true. (콜백 등록 실패여도 전송과 getAirconditionData 조회는 가능하다) */
    val isReady: Boolean get() = driver?.isBinderAlive == true

    fun addListener(l: (Boolean) -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: (Boolean) -> Unit) { listeners.remove(l) }

    // ---------------------------------------------------------------- 연결

    private val death = IBinder.DeathRecipient {
        log("canbussdk 프로세스 종료 감지 → 재연결 예정")
        dropState()
    }

    /**
     * 서비스에 바인드한다(비동기). 연결이 끝나 사용 가능하면 true, 연결 중이거나 실패면 false.
     * 어느 스레드에서 불러도 되고, 실패 시 [BIND_RETRY_MS] 간격으로 다시 시도한다.
     */
    fun ensureBound(ctx: Context): Boolean {
        if (isReady) return true
        val app = ctx.applicationContext ?: return false
        synchronized(lock) {
            if (isReady) return true
            val now = android.os.SystemClock.elapsedRealtime()
            if (conn != null && now - bindStartedAt < BIND_RETRY_MS) return false
            conn?.let { try { app.unbindService(it) } catch (_: Throwable) {} }
            conn = null
            dropState()

            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    driver = service
                    try { service.linkToDeath(death, 0) } catch (_: Throwable) {}
                    worker.execute {
                        registerCallback()
                        refresh()
                        log("canbussdk 연결 완료 (콜백 등록=${registered})")
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    log("canbussdk 연결 끊김")
                    dropState()
                }

                override fun onBindingDied(name: ComponentName) {
                    log("canbussdk 바인딩 종료")
                    dropState()
                    synchronized(lock) { conn = null }
                }
            }
            val intent = Intent(SVC_ACTION).setComponent(ComponentName(PKG, SVC_CLASS))
            val ok = try {
                app.bindService(intent, c, Context.BIND_AUTO_CREATE)
            } catch (e: Throwable) {
                log("bindService 예외: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (ok) {
                conn = c
                bindStartedAt = now
            } else {
                try { app.unbindService(c) } catch (_: Throwable) {}
                log("canbussdk 바인드 실패 (패키지 없음/권한/가시성)")
            }
            return false
        }
    }

    fun release(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute { unregisterCallback() }
        synchronized(lock) {
            conn?.let { try { app?.unbindService(it) } catch (_: Throwable) {} }
            conn = null
        }
        dropState()
    }

    private fun dropState() {
        driver = null
        registered = false
        innerLoop = null
        aqsLoop = null
    }

    // ---------------------------------------------------------------- 수신

    /** 현재 공조 상태를 동기 조회해 내기순환 값을 갱신한다. 값이 없으면 null. */
    fun refresh(): Boolean? {
        val d = driver ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DRIVER_DESC)
            d.transact(TX_GET_AIR, data, reply, 0)
            reply.readException()
            val b = if (reply.readInt() != 0) Bundle.CREATOR.createFromParcel(reply) else null
            if (b == null) {
                null
            } else {
                if (!dumped) {
                    dumped = true
                    log("공조 Bundle 키(${b.size()}): " + b.keySet().take(60).joinToString(","))
                }
                val ds = diagString(b)
                if (ds != lastDiag) {
                    lastDiag = ds
                    log("공조 값[조회] $ds")
                }
                val v = readBool(b, KEY_LOOP)
                val q = readBool(b, KEY_AQS)
                if (q != null) aqsLoop = q
                if (v != null) { innerLoop = v; publish() }
                v
            }
        } catch (e: Throwable) {
            log("공조 상태 조회 실패: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun readBool(b: Bundle, key: String): Boolean? {
        if (!b.containsKey(key)) return null
        return when (val o = b.get(key)) {
            is Boolean -> o
            is Int -> o != 0
            is Byte -> o.toInt() != 0
            else -> null
        }
    }

    /**
     * 리스너(내기 유지 모드)에는 "강제 내기인가"를 전달한다: 내기(loop=1, aqs=0)만 true, 오토/외기는 false.
     * 오토에서는 차량이 실제로 어느 공기를 쓰는지 구분할 수 없으므로(값이 같음), 내기가 아니면 내기 유지 모드가
     * 주기적으로 내기 명령을 다시 보내 강제 내기로 맞춘다.
     */
    private fun publish() {
        val inner = recircMode == MODE_INNER
        for (l in listeners) {
            try { l(inner) } catch (ignored: Throwable) {}
        }
    }

    // ---------------------------------------------------------------- 순환 모드 전환

    @Volatile private var cycleGen = 0
    @Volatile private var cycleTarget: Int? = null

    /** 이미 [target] 으로 전환 중이면 true (중복 요청 방지) */
    fun isCyclingTo(target: Int) = cycleTarget == target

    /**
     * 순환 키(0x13)를 한 번 보내고, 0.7초마다 상태를 다시 읽어 목표 모드가 될 때까지 최대 [MAX_PRESSES]회 누른다.
     * 순환 순서를 몰라도 동작한다. 첫 키 전송 결과만 즉시 돌려주고, 이후는 백그라운드에서 진행하며 로그를 남긴다.
     * 성공하면 null, 실패하면 사유.
     */
    fun cycleTo(target: Int): String? {
        val err = pressAirKey(KEY_RECIRC)
        if (err != null) return err
        val gen = ++cycleGen
        cycleTarget = target
        verifyCycle(target, 1, gen)
        return null
    }

    private fun verifyCycle(target: Int, presses: Int, gen: Int) {
        worker.schedule(Runnable {
            if (gen != cycleGen) return@Runnable          // 더 새로운 요청이 있으면 이 흐름은 중단
            refresh()
            val cur = recircMode
            when {
                cur == target -> { log("순환 전환 완료 → ${modeName(target)} (키 ${presses}회)"); cycleTarget = null }
                cur == null -> { log("순환 상태 확인 불가 → 전환 중단"); cycleTarget = null }
                presses >= MAX_PRESSES -> {
                    log("순환 전환 실패: 키 ${presses}회 후에도 ${modeName(cur)} (목표 ${modeName(target)})")
                    cycleTarget = null
                }
                else -> {
                    val e = pressAirKey(KEY_RECIRC)
                    if (e != null) { log("순환 키 재전송 실패: $e"); cycleTarget = null }
                    else verifyCycle(target, presses + 1, gen)
                }
            }
        }, VERIFY_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    // 진단용: 오토/내기/외기를 구분할 단서가 되는 값들 (판정 로직에는 아직 쓰지 않는다)
    @Volatile private var lastDiag = ""

    /** 번들의 모든 키를 "키=값" 으로 이어 붙인다(오토 내기/외기 비교용). 불리언은 1/0. */
    @Suppress("DEPRECATION")
    private fun diagString(b: Bundle): String = b.keySet().sorted().joinToString(" ") { k ->
        val v = b.get(k)
        k + "=" + when (v) {
            null -> "-"
            is Boolean -> if (v) "1" else "0"
            else -> v.toString()
        }
    }

    @Suppress("DEPRECATION")
    private fun onData(module: String?, bundle: Bundle?) {
        if (module != AIR_MODULE || bundle == null) return
        if (bundle.size() > 0) log("공조 값[콜백] " + diagString(bundle))
        val l = readBool(bundle, KEY_LOOP)
        val q = readBool(bundle, KEY_AQS)
        if (q != null) aqsLoop = q
        if (l != null) innerLoop = l
        if (l != null || q != null) publish()
    }

    /** canbussdk 가 호출하는 콜백. 순정 ICanbusCallback.Stub 과 같은 규약으로 직접 구현했다. */
    private val callback = object : Binder() {
        init { attachInterface(null, CB_DESC) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                when (code) {
                    CB_DATA -> {
                        data.enforceInterface(CB_DESC)
                        val module = data.readString()
                        data.readInt() // 값 종류(1=boolean,2=int,3=float,...)
                        val b = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        onData(module, b)
                        reply?.writeNoException(); reply?.writeInt(1)
                        return true
                    }
                    CB_SEND_DATA_TO_CAN -> {
                        data.enforceInterface(CB_DESC); data.createByteArray()
                        reply?.writeNoException(); reply?.writeInt(0); return true
                    }
                    CB_CAR_STEER -> {
                        data.enforceInterface(CB_DESC); data.readInt()
                        reply?.writeNoException(); reply?.writeInt(0); return true
                    }
                    CB_CAN_VERSION -> {
                        data.enforceInterface(CB_DESC); data.readString()
                        reply?.writeNoException(); return true
                    }
                    CB_CAN_CONNECT, CB_SOUND_CHANNEL -> {
                        data.enforceInterface(CB_DESC); data.readInt()
                        reply?.writeNoException(); return true
                    }
                    CB_SUPPORT_FUNC -> {
                        data.enforceInterface(CB_DESC); data.createStringArray()
                        reply?.writeNoException(); return true
                    }
                    CB_CAN_SERIES -> {
                        data.enforceInterface(CB_DESC); data.readInt(); data.readString()
                        reply?.writeNoException(); return true
                    }
                }
            } catch (e: Throwable) {
                log("콜백 처리 오류: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun registerCallback() {
        val d = driver ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DRIVER_DESC)
            data.writeStrongBinder(callback)
            d.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
            registered = true
        } catch (e: Throwable) {
            registered = false
            log("콜백 등록 실패: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun unregisterCallback() {
        val d = driver ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DRIVER_DESC)
            data.writeStrongBinder(callback)
            d.transact(TX_UNREGISTER, data, reply, 0)
            reply.readException()
        } catch (_: Throwable) {
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    // ---------------------------------------------------------------- 전송

    /**
     * 프레임 [frameType] 에 [data] 를 실어 CAN 박스로 보낸다. 성공하면 null, 실패하면 사유를 돌려준다.
     */
    fun sendFrame(frameType: Int, data: ByteArray): String? {
        val d = driver ?: return "canbussdk 미연결"
        val b = Bundle().apply {
            putString("Array", ITEM_NAME)            // CanBusDefine.Parameter.PARAMETER_ARRAY.value()
            putByte("cmd", frameType.toByte())
            putInt("cmd_int", 0)
            putByteArray(ITEM_NAME, data)
        }
        val p = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            p.writeInterfaceToken(DRIVER_DESC)
            p.writeString(SETTINGS_MODULE)
            p.writeInt(PARAM_ARRAY)
            p.writeInt(1)
            b.writeToParcel(p, 0)
            d.transact(TX_SEND_CMD, p, r, 0)
            r.readException()
            null
        } catch (e: Throwable) {
            "전송 오류: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            p.recycle(); r.recycle()
        }
    }

    /** 공조 키 한 번 누름: [코드,1] 즉시 + 200ms 뒤 [코드,0]. 성공하면 null, 실패하면 사유. */
    fun pressAirKey(keyCode: Int): String? {
        val err = sendFrame(FRAME_AIR, byteArrayOf(keyCode.toByte(), 1))
        if (err != null) return err
        worker.schedule(Runnable {
            val e = sendFrame(FRAME_AIR, byteArrayOf(keyCode.toByte(), 0))
            if (e != null) log("키 해제 전송 실패(코드 $keyCode): $e")
        }, RELEASE_DELAY_MS, TimeUnit.MILLISECONDS)
        return null
    }
}
