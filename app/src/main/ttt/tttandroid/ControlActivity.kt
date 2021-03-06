package ttt.tttandroid

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.SpannableStringBuilder
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.SeekBar
import kotlinx.android.synthetic.main.activity_control.*
import ttt.tttandroid.CalibSetting.MOTOR
import ttt.tttandroid.CalibSetting.SERVO
import ttt.tttandroid.Mode.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

val random = Random()

data class State(
        var lm: Int,
        var rm: Int,
        var servo_hor: Int,
        var servo_ver: Int,
        var stepperDelay: Int,
        var stepperPos: Double
) {
    fun toBytes(): IntArray {
        return intArrayOf(
                lm and 0xff,
                rm and 0xff,
                servo_hor and 0xff,
                servo_ver and 0xff,
                stepperDelay and 0xff,
                (stepperDelay shr 8) and 0xff,
                stepperPos.roundToInt() and 0xff,
                (stepperPos.roundToInt() shr 8) and 0xff
        )
    }
}

private enum class Mode {
    TRAINING, TENNIS, CALIBRATE, RAW
}

private enum class CalibSelected {
    TL, TR, BL, BR
}

private enum class CalibSetting {
    MOTOR, SERVO
}

private enum class VertAngle(val servo: Int) {
    LOW(80), MID(50), HIGH(0)
}

private enum class Pattern(val targets: List<Point2D>? = null) {
    LV_RV(listOf(Point2D(0f, 1f), Point2D(1f, 1f))),
    LA_RA(listOf(Point2D(0f, 0f), Point2D(1f, 0f))),
    LV_RA_RV_LA(listOf(Point2D(0f, 1f), Point2D(1f, 0f), Point2D(1f, 1f), Point2D(0f, 0f))),
    Random;

    fun target(index: Int): Point2D = if (targets == null) {
        Point2D(
                x = if (random.nextBoolean()) 1f else 0f,
                y = if (random.nextBoolean()) 1f else 0f
        )
    } else {
        targets[index % targets.size]
    }
}

private enum class Preset(
        val pattern: Pattern,
        val delay: String,
        val vertAngle: VertAngle
) {
    Easy(Pattern.LV_RV, "5", VertAngle.HIGH),
    Intermediate(Pattern.LA_RA, "5", VertAngle.MID),
    Advanced(Pattern.LV_RA_RV_LA, "1", VertAngle.LOW),
    Random(Pattern.Random, "0", VertAngle.MID)
}

data class Point2D(val x: Float, val y: Float)

class ControlActivity : AppCompatActivity() {
    private val state: State = State(0, 0, 0, 0, 0, .0)
    private lateinit var connection: IBluetoothConnection
    private lateinit var prefs: SharedPreferences
    private var calibSelected: CalibSelected? = null

    var trainingExecutor = Executors.newSingleThreadScheduledExecutor()

    private var target: Point2D? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        prefs = getPreferences(Context.MODE_PRIVATE)

        state.apply {
            lm = lm_slider.progress
            rm = rm_slider.progress
            servo_hor = servo_hor_slider.progress
            servo_ver = servo_ver_slider.progress
            stepperDelay = stepper_delay_slider.progress
            stepperPos = stepper_pos_slider.progress.toDouble()
        }

        lm_slider.setOnSeekBarChangeListener(rawSeekBarListener)
        rm_slider.setOnSeekBarChangeListener(rawSeekBarListener)
        servo_hor_slider.setOnSeekBarChangeListener(rawSeekBarListener)
        servo_ver_slider.setOnSeekBarChangeListener(rawSeekBarListener)
        stepper_delay_slider.setOnSeekBarChangeListener(rawSeekBarListener)
        stepper_pos_slider.setOnSeekBarChangeListener(rawSeekBarListener)

        calibrate_motor_slider.setOnSeekBarChangeListener(calibrateSeekBarListener)
        calibrate_servo_slider.setOnSeekBarChangeListener(calibrateSeekBarListener)

        //disable user interaction
        stepper_actual_pos_slider.setOnTouchListener { _, _ -> true }
        tennis_motor_slider.setOnTouchListener { _, _ -> true }
        tennis_servo_slider.setOnTouchListener { _, _ -> true }

        navigation.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_training -> changeMode(TRAINING)
                R.id.navigation_tennis -> changeMode(TENNIS)
                R.id.navigation_raw -> changeMode(RAW)
                R.id.navigation_calibrate -> changeMode(CALIBRATE)
                else -> return@setOnNavigationItemSelectedListener false
            }
            true
        }

        tennis_table.setOnTouchListener { v, event ->
            val x = (((event.x / v.width) - 0.1f) / 0.8f).clamp(0f, 1f)
            val y = ((event.y / v.height) / 0.77f).clamp(0f, 1f)
            updateStateForTarget(x, y)

            true
        }

        val patternStrings = Pattern.values().map { it.toString().replace('_', ' ') }.toTypedArray()
        pattern_spinner.adapter = ArrayAdapter(this, R.layout.support_simple_spinner_dropdown_item, patternStrings)

        val vertAngleStrings = VertAngle.values().map { it.toString() }.toTypedArray()
        vertangle_spinner.adapter = ArrayAdapter(this, R.layout.support_simple_spinner_dropdown_item, vertAngleStrings)

        vertangle_spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                calibrate_vertical_angle_state.text = "-"
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val vertAngle = VertAngle.values()[position]
                calibrate_vertical_angle_state.text = vertAngle.toString()
                state.servo_ver = vertAngle.servo
                onStateChanged()
            }

        }

        preset_radio_group.setOnCheckedChangeListener { group, checkedId ->
            val index = group.indexOfChild(group.findViewById<RadioButton>(checkedId))
            selectPreset(Preset.values()[index])
        }

        val calibChangeListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                savePrefs()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

        }

        calibrate_servo_slider.setOnSeekBarChangeListener(calibChangeListener)
        calibrate_motor_slider.setOnSeekBarChangeListener(calibChangeListener)

        changeMode(TRAINING)
    }

    private fun dumpPrefs() {
        Log.e("Prefs", prefs.all.entries.joinToString { (key, value) -> "$key: $value" })
    }

    private fun updateStateForTarget(x: Float, y: Float) {
        target = Point2D(x, y)
        tennis_info_text.text = target.toString()

        val params = tennis_ball.layoutParams as ViewGroup.MarginLayoutParams
        params.leftMargin = dpToInt(32 + (322 - 32) * x - 10)
        params.topMargin = dpToInt(-10 + (222 - -10) * y)
        tennis_ball.visibility = View.VISIBLE
        tennis_ball.requestLayout()

        val tl_motor = getPref(CalibSelected.TL, MOTOR).toFloat()
        val tr_motor = getPref(CalibSelected.TR, MOTOR).toFloat()
        val bl_motor = getPref(CalibSelected.BL, MOTOR).toFloat()
        val br_motor = getPref(CalibSelected.BR, MOTOR).toFloat()
        val motor = (tl_motor * (1 - x) * (1 - y) + tr_motor * x * (1 - y) + bl_motor * (1 - x) * y + br_motor * x * y).toInt()

        val tl_servo = getPref(CalibSelected.TL, SERVO).toFloat()
        val tr_servo = getPref(CalibSelected.TR, SERVO).toFloat()
        val bl_servo = getPref(CalibSelected.BL, SERVO).toFloat()
        val br_servo = getPref(CalibSelected.BR, SERVO).toFloat()
        val servo = (tl_servo * (1 - x) * (1 - y) + tr_servo * x * (1 - y) + bl_servo * (1 - x) * y + br_servo * x * y).toInt()

        tennis_motor_slider.progress = motor
        tennis_motor_value.text = motor.toMonoString()
        tennis_servo_slider.progress = servo
        tennis_servo_value.text = servo.toMonoString()

        state.lm = motor
        state.rm = motor
        state.servo_hor = servo
        onStateChanged()
    }

    private fun initConnection() {
        connection = if (MOCK_BT) {
            MockBluetoothConnection
        } else {
            val device = intent.getParcelableExtra<NamedDevice>(Codes.BT_DEVICE_EXTRA)
            connectionStatusText.text = getString(R.string.connecting_to, device.name)
            connecting_spinner.visibility = View.VISIBLE
            BluetoothConnection(this, device, ::onReceive, responseSize) { failed ->
                if (failed) {
                    Log.e("ControlActivity", "failed")
                    finish()
                } else runOnUiThread {
                    connectionStatusText.text = getString(R.string.connected_to, device.name)
                    connecting_spinner.visibility = View.INVISIBLE
                    onStateChanged()
                }
            }
        }
    }

    private fun unSelectPreset() {
        preset_radio_group.clearCheck()
    }

    private fun selectPreset(preset: Preset) {
        pattern_spinner.setSelection(preset.pattern.ordinal)
        ball_delay_edittext.text = SpannableStringBuilder(preset.delay)
        vertangle_spinner.setSelection(preset.vertAngle.ordinal)
    }

    fun onGoTrainingClicked(view: View) {
        if (go_training_button.text == "Stop") {
            training_progressbar.progress = 0
            trainingExecutor.shutdownNow()
            trainingExecutor = Executors.newSingleThreadScheduledExecutor()
            trainingDone()
            return
        }

        navigation.visibility = View.INVISIBLE
        go_training_button.text = "Stop"
        training_progressbar.progress = 0

        val pattern = Pattern.values()[pattern_spinner.selectedItemPosition]
        val ballCount = ball_count_edittext.text.toString().toInt()
        val ballDelay = (ball_delay_edittext.text.toString().toDouble() * 1000).toInt()

        var time = 0L

        repeat(ballCount) { i ->
            time += ballDelay.takeUnless { it == 0 } ?: random.nextInt(6000) + 1000
            val target = pattern.target(i)

            trainingExecutor.schedule({
                Log.e("Index", "$i")
                runOnUiThread {
                    updateStateForTarget(target.x, target.y)
                    onFireButtonClicked(fire_button)
                    training_progressbar.progress = (100f / ballCount * (i + 1)).toInt()

                    if (i == ballCount - 1) {
                        trainingDone()
                    }
                }
            }, time, TimeUnit.MILLISECONDS)
        }
    }

    fun trainingDone() {
        navigation.visibility = View.VISIBLE
        go_training_button.text = "Go"
    }

    private fun changeMode(mode: Mode) {
        val (trainingVis, tennisVis, calibVis, rawVis) = when (mode) {
            TRAINING -> listOf(View.VISIBLE, View.GONE, View.GONE, View.GONE)
            TENNIS -> listOf(View.GONE, View.VISIBLE, View.GONE, View.GONE)
            CALIBRATE -> listOf(View.GONE, View.GONE, View.VISIBLE, View.GONE)
            RAW -> listOf(View.GONE, View.GONE, View.GONE, View.VISIBLE)
        }

        view_training.visibility = trainingVis
        view_tennis.visibility = tennisVis
        view_calibrate.visibility = calibVis
        view_raw.visibility = rawVis
    }

    private fun dpToInt(value: Float) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    fun disconnectClicked(view: View) {
        finish()
    }

    val responseSize = 3
    val MSG_SPEED = 0
    val MSG_STEPPER = 1

    private fun onReceive(data: IntArray) = runOnUiThread {
        if (data.size != responseSize)
            throw RuntimeException("data size ${data.size} doesn't match $responseSize")

        val type = data[0]
        val content = data[1] + (data[2] shl 8)

        when (type) {
            MSG_SPEED -> {
                val deltaT = content.toDouble() / 1000.0
                val deltaX = 0.034
                val v = deltaX / deltaT

                speed_value_ms.text = "%.2f".format(v)
                speed_value_kmh.text = "%.2f".format(v * 3.6)
            }
            MSG_STEPPER -> {
                stepper_actual_pos_value.text = content.toMonoString()
                stepper_actual_pos_slider.progress = content

                if (content == targetStepperPos) {
                    fire_button.isEnabled = true
                }
            }
            else -> Unit//throw RuntimeException("unknown type $type")
        }
    }

    var targetStepperPos = state.stepperPos.roundToInt()
    var direction = true

    fun onFireButtonClicked(view: View) {
        state.stepperPos += 200f / 3f
        direction = !direction
        targetStepperPos = state.stepperPos.roundToInt()
        onStateChanged()
    }

    override fun onStop() {
        connection.destroy()
        super.onStop()
    }

    override fun onStart() {
        initConnection()
        super.onStart()
    }

    fun onStateChanged() {
        val data = state.toBytes()
        connection.write(data)

        Log.e("Written", state.toString())

        lm_value.text = state.lm.toMonoString()
        rm_value.text = state.rm.toMonoString()
        servo_hor_value.text = state.servo_hor.toMonoString()
        servo_ver_value.text = state.servo_ver.toMonoString()
        stepper_speed_value.text = state.stepperDelay.toMonoString()
        stepper_pos_value.text = state.stepperPos.roundToInt().toMonoString()

        lm_slider.progress = state.lm
        rm_slider.progress = state.rm
        servo_hor_slider.progress = state.servo_hor
        servo_ver_slider.progress = state.servo_ver
        stepper_delay_slider.progress = state.stepperDelay
        stepper_pos_slider.progress = state.stepperPos.roundToInt()
    }

    private fun currentVertAngleState() = VertAngle.values()[vertangle_spinner.selectedItemPosition]

    private fun getKey(selected: CalibSelected, setting: CalibSetting) =
            "${currentVertAngleState()}:$selected:$setting".toLowerCase()

    private fun getPref(selected: CalibSelected, setting: CalibSetting) =
            prefs.getInt(getKey(selected, setting), 0)

    fun onCalibButtonClicked(view: View) {
        val select = when (view.id) {
            R.id.calib_tl_button -> CalibSelected.TL
            R.id.calib_tr_button -> CalibSelected.TR
            R.id.calib_bl_button -> CalibSelected.BL
            R.id.calib_br_button -> CalibSelected.BR
            else -> null
        }
        calibSelected = select

        calib_tl_button.isEnabled = true
        calib_tr_button.isEnabled = true
        calib_bl_button.isEnabled = true
        calib_br_button.isEnabled = true

        if (select == null) {
            calibrate_motor_slider.progress = 0
            calibrate_motor_value.text = "xxxx"
            calibrate_servo_slider.progress = 0
            calibrate_servo_value.text = "xxxx"
            calibrate_cancel_button.isEnabled = false
        } else {
            view.isEnabled = false
            calibrate_cancel_button.isEnabled = true

            calibrate_motor_slider.progress = getPref(select, MOTOR)
            calibrate_motor_value.text = getPref(select, MOTOR).toMonoString()
            calibrate_servo_slider.progress = getPref(select, SERVO)
            calibrate_servo_value.text = getPref(select, SERVO).toMonoString()
        }
    }

    private val calibrateSeekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (calibSelected == null)
                return

            when (seekBar) {
                calibrate_motor_slider -> calibrate_motor_value.text = progress.toMonoString()
                calibrate_servo_slider -> calibrate_servo_value.text = progress.toMonoString()
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) {}

    }

    private fun savePrefs() {
        val selected = calibSelected ?: return

        val edit = prefs.edit()
        edit.putInt(getKey(selected, MOTOR), calibrate_motor_slider.progress)
        edit.putInt(getKey(selected, SERVO), calibrate_servo_slider.progress)
        edit.apply()
    }

    private val rawSeekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) {
                return
            }

            when (seekBar) {
                lm_slider -> state.lm = progress
                rm_slider -> state.rm = progress
                servo_hor_slider -> state.servo_hor = progress
                servo_ver_slider -> state.servo_ver = progress
                stepper_delay_slider -> state.stepperDelay = progress
                stepper_pos_slider -> state.stepperPos = progress.toDouble()
            }
            onStateChanged()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}

private fun Float.clamp(min: Float, max: Float) = when {
    this < min -> min
    this > max -> max
    else -> this
}

private fun Int.toMonoString() = this.toString().leftPad(4)

private fun String.leftPad(length: Int) = if (this.length < length) " ".repeat(length - this.length) + this else this