package com.example.andrax92.myapplication

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.andrax92.myapplication.databinding.ActivityArduinoDataBinding
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.util.*
import kotlin.collections.ArrayList


class ArduinoDataActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityArduinoDataBinding
    private lateinit var textViewContent: TextView
    // Accelerometer & Gyroscope manager
    private lateinit var sensorManager: SensorManager
    private lateinit var accelSensor: Sensor
    private lateinit var gyroSensor: Sensor
    // Accelerometer & Gyroscope value arrays
    private val decFormat: DecimalFormat = DecimalFormat("#.#######") // Truncate to 7 decimals
    private var accelArray: ArrayList<String> = ArrayList(Collections.nCopies(3, "#.##"))
    private var gyroArray: ArrayList<String> = ArrayList(Collections.nCopies(3, "#.##"))
    // Serial USB service
    private lateinit var usbService: UsbService
    private val mHandler: MessageHandler = MessageHandler(this@ArduinoDataActivity)
    // Storage files helpers
    private lateinit var path: String
    private lateinit var file: File

    private var comesFromStop: Boolean = false
    private var recordFalling: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArduinoDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Set up the app content
        textViewContent = binding.tvContent
        textViewContent.movementMethod = ScrollingMovementMethod()

        // Keep screen on so that logging doesn't stop
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Check permissions for writing data files in external storage
        if (!isPermissionGranted(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            ActivityCompat.requestPermissions(this, STORAGE_PERMISSIONS, REQUEST_STORAGE_PERMISSION)
        } else {
            createFile(false)
        }

        // Set accelerometer and gyroscope sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        display("Please plug an Arduino via OTG.\n")
        display("\nPRESS START TO RECORD DATA\n")
    }

    private fun createFile(falling: Boolean) {
        var filePrefix = FILE_PREFIX

        if (falling) {
            filePrefix = FALL_FILE_PREFIX
        }

        if (isExternalStorageAvailable() && !isExternalStorageReadOnly()) {
            path = Environment.getExternalStorageDirectory().path

            var i = 1
            file = File("$path$filePrefix$i.txt")
            while (file.exists()) {
                i++
                file = File("$path$filePrefix$i.txt")
            }

            val created = file.createNewFile()

            if (created) {
                display("File created. ${file.absolutePath}\n")
            } else {
                display("File not created\n")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setFilters()
        startService(UsbService::class.java, usbConnection, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!comesFromStop) {
            unregisterReceiver(mUsbReceiver)
            unbindService(usbConnection)
            sensorManager.unregisterListener(this)
        }
    }

    fun startReadingArduinoData(view: View) {
        if (comesFromStop) {
            setFilters()
            startService(UsbService::class.java, usbConnection, null)
            createFile(recordFalling)
        }
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_NORMAL)
        val start = "s"
        usbService.write(start.toByteArray())
        display("\nRECORDING DATA\n")
    }

    fun startReadingFallingData(view: View) {
        stopReading()
        if (!recordFalling) {
            createFile(true)
            recordFalling = true
            binding.button2.text = "FALLING"
            display("\nPRESS START TO RECORD FALL DATA\n")
        } else {
            createFile(false)
            recordFalling = false
            binding.button2.text = "NORMAL"
            display("\nPRESS START TO RECORD DATA\n")
        }

    }

    fun stopReadingData(view: View) {
        val stop = "t"
        usbService.write(stop.toByteArray())
        stopReading()
    }

    private fun stopReading() {
        Toast.makeText(this, "Stopping data retrieval", Toast.LENGTH_LONG).show()
        if (mUsbReceiver.isOrderedBroadcast) {
            unregisterReceiver(mUsbReceiver)
            unbindService(usbConnection)
            sensorManager.unregisterListener(this)
        }
        comesFromStop = true
        display("\nDATA RETRIEVAL STOPPED\n")
    }

    private fun write(data: String) {
        try {
            File(file.absolutePath).appendText(data)
        } catch (e: IOException) {
            Log.e("Error", "Error writing data \n$e")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorName: String = event?.sensor!!.name
        if (sensorName.contains("Gravity")) {
            accelArray[0] = decFormat.format(event.values[0] / 9.8).toString().replace(",", ".")
            accelArray[1] = decFormat.format(event.values[1] / 9.8).toString().replace(",", ".")
            accelArray[2] = decFormat.format(event.values[2] / 9.8).toString().replace(",", ".")
            Log.d(LOG_TAG, "AccelValues: $accelArray")
        }
        if (sensorName.contains("Gyroscope")) {
            gyroArray[0] = decFormat.format(event.values[0]).toString().replace(",", ".")
            gyroArray[1] = decFormat.format(event.values[1]).toString().replace(",", ".")
            gyroArray[2] = decFormat.format(event.values[2]).toString().replace(",", ".")
            Log.d(LOG_TAG, "GyroValues: $gyroArray")
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // Nothing here. Same accuracy all the time
    }

    private fun display(message: String) {
        runOnUiThread {
            binding.tvContent.append(message)
        }
    }

    private fun startService(service: Class<*>, serviceConnection: ServiceConnection, extras: Bundle?) {
        if (!UsbService.SERVICE_CONNECTED) {
            val startService = Intent(this, service)
            if (extras != null && !extras.isEmpty) {
                val keys = extras.keySet()
                for (key in keys) {
                    val extra = extras.getString(key)
                    startService.putExtra(key, extra)
                }
            }
            startService(startService)
        }
        val bindingIntent = Intent(this, service)
        bindService(bindingIntent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun setFilters() {
        val filter = IntentFilter()
        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED)
        filter.addAction(UsbService.ACTION_NO_USB)
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED)
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED)
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED)
        registerReceiver(mUsbReceiver, filter)
    }

    private fun isExternalStorageReadOnly(): Boolean {
        val extStorageState = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED_READ_ONLY == extStorageState
    }

    private fun isExternalStorageAvailable(): Boolean {
        val extStorageState = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == extStorageState
    }

    private fun isPermissionGranted(callerActivity: Activity?, permission: String?): Boolean {
        return ContextCompat.checkSelfPermission(callerActivity!!, permission!!) == PackageManager.PERMISSION_GRANTED
    }


    private val usbConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(arg0: ComponentName, arg1: IBinder) {
            usbService = (arg1 as UsbService.UsbBinder).service
            usbService.setHandler(mHandler)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {

        }
    }

    /*
     * Notifications from UsbService will be received here.
     */
    private val mUsbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbService.ACTION_USB_PERMISSION_GRANTED ->
                    Toast.makeText( context, "USB Ready", Toast.LENGTH_SHORT).show()
                UsbService.ACTION_USB_PERMISSION_NOT_GRANTED ->
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show()
                UsbService.ACTION_NO_USB ->
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show()
                UsbService.ACTION_USB_DISCONNECTED ->
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show()
                UsbService.ACTION_USB_NOT_SUPPORTED ->
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /*
     * This handler will be passed to UsbService. Data received from serial port is displayed through this handler
     */
    private class MessageHandler(activity: ArduinoDataActivity) : Handler(Looper.getMainLooper()) {

        private val mActivity: ArduinoDataActivity = activity
        var accelerationValues = activity.accelArray
        var gyroscopeValues = activity.gyroArray

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                UsbService.MESSAGE_FROM_SERIAL_PORT -> {
                    val data = msg.obj as String
                    val completeData = concatenateMessage(data.trim())
                    try {
                        File(mActivity.file.absolutePath).appendText(completeData)
                    } catch (e: IOException) {
                        Log.e("Error", "Error writing data \n$e")
                        mActivity.display("Error writing data: $e")
                    }
                }
                UsbService.CTS_CHANGE ->
                    Toast.makeText(mActivity, "CTS_CHANGE", Toast.LENGTH_LONG).show()
                UsbService.DSR_CHANGE ->
                    Toast.makeText(mActivity, "DSR_CHANGE", Toast.LENGTH_LONG).show()
            }
        }

        fun getAccelArray() {

        }

        private fun concatenateMessage(data: String): String {
            var appendedData = "$data,${accelerationValues[0]},${accelerationValues[1]},${accelerationValues[2]},${gyroscopeValues[0]},${gyroscopeValues[1]},${gyroscopeValues[2]}\n"
            val splitData = appendedData.split(",")
            if (splitData.size < 10) {
                appendedData = "$data,${mActivity.accelArray[0]},${mActivity.accelArray[1]},${mActivity.accelArray[2]},${mActivity.gyroArray[0]},${mActivity.gyroArray[1]},${mActivity.gyroArray[2]}\n"
            }
            return appendedData
        }
    }

    companion object {
        private const val LOG_TAG = "ArduinoDataActivity"
        // Storage constants
        private const val REQUEST_STORAGE_PERMISSION: Int = 2
        private val STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE)
        private const val FILE_PREFIX = "/DATA_"
        private const val FALL_FILE_PREFIX = "/DATA_FALL_"
    }
}