package com.parent.familycontrol

import android.animation.ObjectAnimator
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.parent.familycontrol.databinding.ActivityMainBinding
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * شاشتان:
 *  1) شاشة القفل: لوحة أرقام + 4 نقاط (إنشاء الرمز عند أول تشغيل ثم تسجيل الدخول).
 *  2) شاشة التحكم: مفتاح تبديل للواي فاي ومفتاح للبلوتوث.
 *
 * ملاحظة تقنية: التطبيق يستهدف targetSdk 28 (انظر app/build.gradle) لأن
 * WifiManager.setWifiEnabled() و BluetoothAdapter.enable() تتوقفان عن العمل
 * للتطبيقات العادية التي تستهدف أندرويد 10+.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { LOGIN, SETUP_NEW, SETUP_CONFIRM }
    private enum class RadioState { ON, OFF, TURNING_ON, TURNING_OFF, UNKNOWN }

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiManager: WifiManager
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var unlocked = false
    private var changingPin = false
    private var mode = Mode.LOGIN
    private val entered = StringBuilder()
    private var firstPin = ""
    private var busy = false          // يمنع الإدخال أثناء حركة الخطأ
    private var updating = false      // يمنع استدعاء المفاتيح عند تحديثها برمجياً

    private val dotViews by lazy {
        listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
    }

    // تحديث الحالة فور تغيّرها في النظام
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateStatusLabels()
    }
    private var receiverRegistered = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        bluetoothAdapter =
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        // لوحة الأرقام
        listOf(
            binding.key0 to "0", binding.key1 to "1", binding.key2 to "2",
            binding.key3 to "3", binding.key4 to "4", binding.key5 to "5",
            binding.key6 to "6", binding.key7 to "7", binding.key8 to "8",
            binding.key9 to "9"
        ).forEach { (view, digit) -> view.setOnClickListener { onDigit(digit) } }
        binding.keyBack.setOnClickListener { onBackspace() }

        // روابط نصية بخط تحتها
        listOf(binding.btnCancelSetup, binding.btnChangePin, binding.btnLock).forEach {
            it.paintFlags = it.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }
        binding.btnCancelSetup.setOnClickListener {
            changingPin = false
            showControlScreen()
        }
        binding.btnChangePin.setOnClickListener {
            changingPin = true
            showPinScreen()
        }
        binding.btnLock.setOnClickListener { lockApp() }

        // مفاتيح التبديل
        binding.wifiSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating && unlocked) setWifi(checked)
        }
        binding.btSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating && unlocked) setBluetooth(checked)
        }

        showPinScreen()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(stateReceiver, filter)
        receiverRegistered = true
        updateStatusLabels()
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        // قفل تلقائي بمجرد مغادرة التطبيق حتى لا يستخدمه الطفل بعد الأب/الأم
        if (!isChangingConfigurations) lockApp()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Screens

    private fun hasPin() = prefs.contains(KEY_HASH)

    private fun showPinScreen() {
        unlocked = false
        busy = false
        firstPin = ""
        binding.controlScreen.visibility = View.GONE
        binding.lockScreen.visibility = View.VISIBLE
        binding.btnCancelSetup.visibility = if (changingPin) View.VISIBLE else View.GONE
        binding.pinMessage.visibility = View.INVISIBLE
        mode = if (!hasPin() || changingPin) Mode.SETUP_NEW else Mode.LOGIN
        resetEntry()
        applyTexts()
    }

    private fun showControlScreen() {
        unlocked = true
        busy = false
        resetEntry()
        binding.lockScreen.visibility = View.GONE
        binding.controlScreen.visibility = View.VISIBLE
        updateStatusLabels()
    }

    private fun lockApp() {
        changingPin = false
        showPinScreen()
    }

    private fun applyTexts() {
        val (title, sub) = when (mode) {
            Mode.LOGIN -> R.string.lock_title to R.string.lock_sub
            Mode.SETUP_NEW -> R.string.setup_title to R.string.setup_sub
            Mode.SETUP_CONFIRM -> R.string.confirm_title to R.string.confirm_sub
        }
        binding.pinTitle.setText(title)
        binding.pinSub.setText(sub)
    }

    // ---------------------------------------------------------------- PIN keypad

    private fun onDigit(d: String) {
        if (busy || entered.length >= PIN_LENGTH) return
        if (mode == Mode.LOGIN && isLockedOut()) {
            showMessage(getString(R.string.pin_locked, lockRemainingSeconds()))
            return
        }
        entered.append(d)
        binding.pinMessage.visibility = View.INVISIBLE
        renderDots()
        if (entered.length == PIN_LENGTH) {
            busy = true
            handler.postDelayed({ busy = false; onPinComplete() }, 120)
        }
    }

    private fun onBackspace() {
        if (busy || entered.isEmpty()) return
        entered.deleteCharAt(entered.length - 1)
        renderDots()
    }

    private fun resetEntry() {
        entered.setLength(0)
        renderDots()
    }

    private fun renderDots(error: Boolean = false) {
        dotViews.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                when {
                    i >= entered.length -> R.drawable.dot_empty
                    error -> R.drawable.dot_error
                    else -> R.drawable.dot_filled
                }
            )
        }
    }

    private fun onPinComplete() {
        val pin = entered.toString()
        when (mode) {
            Mode.LOGIN -> verifyPin(pin)
            Mode.SETUP_NEW -> {
                firstPin = pin
                mode = Mode.SETUP_CONFIRM
                resetEntry()
                applyTexts()
            }
            Mode.SETUP_CONFIRM -> {
                if (pin == firstPin) {
                    savePin(pin)
                } else {
                    firstPin = ""
                    fail(getString(R.string.pin_mismatch)) {
                        mode = Mode.SETUP_NEW
                        applyTexts()
                    }
                }
            }
        }
    }

    private fun verifyPin(pin: String) {
        val now = System.currentTimeMillis()
        val saltB64 = prefs.getString(KEY_SALT, null)
        val savedHash = prefs.getString(KEY_HASH, null)
        val ok = saltB64 != null && savedHash != null &&
            MessageDigest.isEqual(
                hashPin(pin, Base64.decode(saltB64, Base64.NO_WRAP)).toByteArray(),
                savedHash.toByteArray()
            )

        if (ok) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
            showControlScreen()
            return
        }

        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILS, fails)
        // بعد كل 5 محاولات فاشلة: انتظار 60 ثانية
        if (fails % MAX_ATTEMPTS == 0) editor.putLong(KEY_LOCK_UNTIL, now + LOCK_MILLIS)
        editor.apply()

        val msg = if (isLockedOut()) {
            getString(R.string.pin_locked, lockRemainingSeconds())
        } else {
            getString(R.string.pin_wrong, fails)
        }
        fail(msg)
    }

    private fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, hashPin(pin, salt))
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
        changingPin = false
        toast(getString(R.string.pin_saved))
        showControlScreen()
    }

    /** نقاط حمراء + اهتزاز + رسالة، ثم تصفير الإدخال. */
    private fun fail(message: String, after: (() -> Unit)? = null) {
        busy = true
        renderDots(error = true)
        showMessage(message)
        ObjectAnimator.ofFloat(
            binding.dots, "translationX", 0f, -dp(8), dp(8), -dp(8), dp(8), 0f
        ).setDuration(400).start()
        handler.postDelayed({
            busy = false
            resetEntry()
            after?.invoke()
        }, 420)
    }

    private fun showMessage(msg: String) {
        binding.pinMessage.text = msg
        binding.pinMessage.visibility = View.VISIBLE
    }

    private fun isLockedOut() = System.currentTimeMillis() < prefs.getLong(KEY_LOCK_UNTIL, 0L)

    private fun lockRemainingSeconds(): Int =
        (((prefs.getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()) / 1000).toInt() + 1)
            .coerceAtLeast(1)

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- Wi-Fi

    @Suppress("DEPRECATION")
    private fun setWifi(enable: Boolean) {
        try {
            val accepted = wifiManager.setWifiEnabled(enable)
            if (!accepted) {
                toast(getString(R.string.wifi_failed))
                updateStatusLabels() // يعيد المفتاح لحالته الحقيقية
            }
        } catch (e: SecurityException) {
            toast(getString(R.string.permission_missing))
            updateStatusLabels()
        }
        handler.postDelayed({ updateStatusLabels() }, 1500)
    }

    // ---------------------------------------------------------------- Bluetooth

    @Suppress("DEPRECATION", "MissingPermission")
    private fun setBluetooth(enable: Boolean) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            toast(getString(R.string.bt_unsupported))
            updateStatusLabels()
            return
        }
        try {
            val accepted = if (enable) adapter.enable() else adapter.disable()
            // تُرجع false أيضاً إذا كان البلوتوث بالحالة المطلوبة أصلاً
            if (!accepted && adapter.isEnabled != enable) {
                toast(getString(R.string.bt_failed))
                updateStatusLabels()
            }
        } catch (e: SecurityException) {
            toast(getString(R.string.permission_missing))
            updateStatusLabels()
        }
        handler.postDelayed({ updateStatusLabels() }, 1500)
    }

    // ---------------------------------------------------------------- Status

    private fun updateStatusLabels() {
        if (!::binding.isInitialized) return

        val wifi = when (wifiManager.wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> RadioState.ON
            WifiManager.WIFI_STATE_ENABLING -> RadioState.TURNING_ON
            WifiManager.WIFI_STATE_DISABLING -> RadioState.TURNING_OFF
            WifiManager.WIFI_STATE_DISABLED -> RadioState.OFF
            else -> RadioState.UNKNOWN
        }
        renderRadio(binding.wifiSwitch, binding.wifiState, wifi)

        val bt = try {
            when (bluetoothAdapter?.state) {
                BluetoothAdapter.STATE_ON -> RadioState.ON
                BluetoothAdapter.STATE_TURNING_ON -> RadioState.TURNING_ON
                BluetoothAdapter.STATE_TURNING_OFF -> RadioState.TURNING_OFF
                BluetoothAdapter.STATE_OFF, null -> RadioState.OFF
                else -> RadioState.UNKNOWN
            }
        } catch (e: SecurityException) {
            RadioState.UNKNOWN
        }
        renderRadio(binding.btSwitch, binding.btState, bt)
    }

    private fun renderRadio(sw: SwitchCompat, label: TextView, state: RadioState) {
        label.setText(
            when (state) {
                RadioState.ON -> R.string.state_on
                RadioState.OFF -> R.string.state_off
                RadioState.TURNING_ON -> R.string.state_turning_on
                RadioState.TURNING_OFF -> R.string.state_turning_off
                RadioState.UNKNOWN -> R.string.state_unknown
            }
        )
        label.setTextColor(
            getColor(
                when (state) {
                    RadioState.ON -> R.color.teal
                    RadioState.OFF -> R.color.red
                    else -> R.color.ink_dim
                }
            )
        )
        updating = true
        sw.isChecked = state == RadioState.ON || state == RadioState.TURNING_ON
        updating = false
    }

    // ---------------------------------------------------------------- Utils

    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PREFS = "family_control_prefs"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_FAILS = "pin_fails"
        private const val KEY_LOCK_UNTIL = "pin_lock_until"
        private const val PIN_LENGTH = 4
        private const val MAX_ATTEMPTS = 5
        private const val LOCK_MILLIS = 60_000L
        private const val PBKDF2_ITERATIONS = 20_000
    }
}
