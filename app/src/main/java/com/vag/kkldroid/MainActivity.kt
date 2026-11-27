package com.vag.kkldroid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.vag.kkldroid.USB_PERMISSION"
    private var usbPort: UsbSerialPort? = null
    private var isConnectedToEcu = false
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvStatus: TextView
    private lateinit var tvEcuInfo: TextView
    private lateinit var tvOutput: TextView
    private lateinit var spinnerModule: Spinner
    private lateinit var etGroup: EditText
    private lateinit var tvCells: List<TextView>

    private val vagDtcDict = mapOf(
        "16486" to "P0102 - DMRV (G70): nizkiy signal",
        "16487" to "P0103 - DMRV (G70): vysokiy signal",
        "16514" to "P0130 - Lambda 1: sboy cepi",
        "16555" to "P0171 - Bednaya smes",
        "16556" to "P0172 - Bogataya smes",
        "16684" to "P0300 - Propuski vosplameneniya",
        "16685" to "P0301 - Propuski cil. 1",
        "16686" to "P0302 - Propuski cil. 2",
        "16687" to "P0303 - Propuski cil. 3",
        "16688" to "P0304 - Propuski cil. 4",
        "16705" to "P0321 - DPKV (G28): nedostoverny signal",
        "16725" to "P0341 - DPRV (G40): nedostoverny signal",
        "16825" to "P0441 - Ventilyaciya baka EVAP",
        "17535" to "P1127 - Pereobogashenie smesi",
        "17536" to "P1128 - Pereobednenie smesi",
        "17705" to "P1297 - Padenie davleniya turbina-drossel",
        "17965" to "P1557 - Pereduv turbiny",
        "17978" to "P1570 - Blokirovka EBU (Immo)",
        "18010" to "P1602 - Nizkoe napryazhenie kl. 30"
    )
    private val modules = listOf(
        Pair("01 - Engine (1.8T AWM KWP2000)", 0x01),
        Pair("02 - AT Transmission (KWP1281)", 0x02),
        Pair("03 - ABS Brakes (KWP1281)", 0x03),
        Pair("15 - Airbags (KWP1281)", 0x15),
        Pair("17 - Instruments (KWP1281)", 0x17),
        Pair("46 - Central Conv. (KWP1281)", 0x46)
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) connectUsbAndEcu() else log("USB permission denied.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 24, 24, 24)
        }

        tvStatus = TextView(this).apply {
            text = "USB: Not connected"
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(20, 20, 20, 20)
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(tvStatus)

        spinnerModule = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modules.map { it.first })
        }
        root.addView(spinnerModule)

        val btnConnect = Button(this).apply { text = "CONNECT ECU" }
        root.addView(btnConnect)

        tvEcuInfo = TextView(this).apply {
            text = "ECU: none"
            setTextColor(Color.LTGRAY)
            setPadding(10, 10, 10, 10)
        }
        root.addView(tvEcuInfo)

        val rowDtc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnReadDtc = Button(this).apply { text = "02 - DTC" }
        val btnClearDtc = Button(this).apply { text = "05 - CLEAR" }
        rowDtc.addView(btnReadDtc, LinearLayout.LayoutParams(0, -2, 1f))
        rowDtc.addView(btnClearDtc, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(rowDtc)

        val rowGrp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etGroup = EditText(this).apply {
            setText("001")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            gravity = Gravity.CENTER
        }
        val btnReadGroup = Button(this).apply { text = "08 - GROUP" }
        val btnBasic060 = Button(this).apply { text = "060 TBA" }
        rowGrp.addView(etGroup, LinearLayout.LayoutParams(160, -2))
        rowGrp.addView(btnReadGroup, LinearLayout.LayoutParams(0, -2, 1f))
        rowGrp.addView(btnBasic060, LinearLayout.LayoutParams(-2, -2))
        root.addView(rowGrp)

        tvCells = (1..4).map { idx ->
            TextView(this).apply {
                text = "Field $idx: ---"
                setTextColor(Color.parseColor("#80CBC4"))
                setBackgroundColor(Color.parseColor("#263238"))
                setPadding(16, 12, 16, 12)
            }.also { root.addView(it) }
        }

        tvOutput = TextView(this).apply {
            text = "Ready...\n"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(tvOutput, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        btnConnect.setOnClickListener { requestUsbConnection() }
        btnReadDtc.setOnClickListener { readFaultCodes() }
        btnClearDtc.setOnClickListener { clearFaultCodes() }
        btnReadGroup.setOnClickListener { toggleGroupPolling() }
        btnBasic060.setOnClickListener { runThrottleAdaptation060() }
    }
    private fun requestUsbConnection() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) { log("CH340/FTDI not found!"); return }
        val driver = drivers.first()
        if (!manager.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(driver.device, PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags))
        } else connectUsbAndEcu()
    }

    private fun connectUsbAndEcu() {
        pollingJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull() ?: return@launch
                val connection = manager.openDevice(driver.device) ?: return@launch
                usbPort?.close()
                usbPort = driver.ports.first()
                usbPort?.open(connection)
                usbPort?.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "USB: Connected (${driver.device.productName ?: "CH340"})"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                }
                val addr = modules[spinnerModule.selectedItemPosition].second
                if (addr == 0x01) initKwp2000Engine() else initKwp1281Slow(addr)
            } catch (e: Exception) { log("Port error: ${e.message}") }
        }
    }

    private suspend fun initKwp2000Engine() {
        val port = usbPort ?: return
        log("Fast Init (KWP2000) for 01 Engine...")
        port.setParameters(360, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.write(byteArrayOf(0x00), 50)
        delay(25)
        port.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val resp = sendAndReceive(byteArrayOf(0x81.toByte(), 0x10, 0xF1.toByte(), 0x81.toByte(), 0x03), 400)
        if (resp.isNotEmpty() && resp.contains(0xC1.toByte())) {
            isConnectedToEcu = true
            log("OK! Bosch ME7.5 connected: ${resp.toHex()}")
            val idResp = sendKwp2000Payload(byteArrayOf(0x21, 0x9B.toByte()))
            val ascii = idResp.filter { it in 32..126 }.map { it.toInt().toChar() }.joinToString("")
            withContext(Dispatchers.Main) { tvEcuInfo.text = "ECU: ${ascii.ifEmpty { "Bosch ME7.5 AWM" }}" }
        } else {
            isConnectedToEcu = false
            log("No Fast Init (${resp.toHex()}). Trying 5-baud...")
            initKwp1281Slow(0x01)
        }
    }

    private suspend fun initKwp1281Slow(address: Int) {
        val port = usbPort ?: return
        log("5-baud init 0x${address.toString(16).uppercase()}...")
        port.setParameters(5, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.write(byteArrayOf(address.toByte()), 2200)
        delay(2100)
        port.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val buf = ByteArray(32)
        val len = port.read(buf, 500)
        if (len > 0) {
            val sync = buf.copyOf(len)
            log("Sync: ${sync.toHex()}")
            if (sync.size >= 3) {
                delay(30)
                port.write(byteArrayOf((sync.last().toInt() xor 0xFF).toByte()), 100)
                isConnectedToEcu = true
                log("Connected KWP1281!")
            }
        } else log("No response from 0x${address.toString(16)}.")
    }

    private fun readFaultCodes() {
        if (!isConnectedToEcu) { log("Press Connect first!"); return }
        scope.launch(Dispatchers.IO) {
            log("Reading DTCs (18 00 FF 00)...")
            val resp = sendKwp2000Payload(byteArrayOf(0x18, 0x00, 0xFF.toByte(), 0x00))
            log("DTC Raw: ${resp.toHex()}")
            val idx = resp.indexOf(0x58.toByte())
            if (idx != -1 && resp.size > idx + 1) {
                val count = resp[idx + 1].toInt() and 0xFF
                if (count == 0) log("0 DTCs found.") else {
                    var pos = idx + 2
                    for (i in 0 until count) {
                        if (pos + 2 < resp.size) {
                            val code = ((resp[pos].toInt() and 0xFF) shl 8) or (resp[pos + 1].toInt() and 0xFF)
                            val st = resp[pos + 2].toInt() and 0xFF
                            val cStr = code.toString().padStart(5, '0')
                            val hStr = code.toString(16).uppercase().padStart(4, '0')
                            log("- $cStr ($hStr) ${if ((st and 0x80) != 0) "[Interm]" else "[Static]"}: ${vagDtcDict[cStr] ?: "VAG DTC"}")
                            pos += 3
                        }
                    }
                }
            }
        }
    }

    private fun clearFaultCodes() {
        if (!isConnectedToEcu) { log("Press Connect first!"); return }
        scope.launch(Dispatchers.IO) {
            val resp = sendKwp2000Payload(byteArrayOf(0x14, 0xFF.toByte(), 0x00))
            log(if (resp.contains(0x54.toByte())) "DTCs cleared!" else "Clear resp: ${resp.toHex()}")
        }
    }

    private fun toggleGroupPolling() {
        if (pollingJob?.isActive == true) { pollingJob?.cancel(); log("Stopped."); return }
        if (!isConnectedToEcu) { log("Connect first!"); return }
        val g = etGroup.text.toString().toIntOrNull() ?: 1
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val resp = sendKwp2000Payload(byteArrayOf(0x21, g.toByte()))
                val idx = resp.indexOf(0x61.toByte())
                if (idx != -1 && resp.size > idx + 2) parseVagGroup(g, resp.sliceArray((idx + 2) until resp.size))
                delay(250)
            }
        }
    }

    private suspend fun parseVagGroup(group: Int, data: ByteArray) {
        val cells = Array(4) { "---" }
        for (i in 0 until 4) {
            val off = i * 2
            if (off + 1 < data.size) {
                val a = data[off].toInt() and 0xFF
                val b = data[off + 1].toInt() and 0xFF
                cells[i] = when {
                    group == 1 && i == 0 -> "${b * 40} RPM"
                    group == 1 && i == 1 -> "${(b * 0.75 - 48).toInt()} C"
                    group == 3 && i == 1 -> "${String.format("%.1f", b * 0.25)} g/s"
                    group == 115 && i == 2 -> "${b * 10} mbar (Req)"
                    group == 115 && i == 3 -> "${b * 10} mbar (Act)"
                    else -> "A:$a B:$b"
                }
            }
        }
        withContext(Dispatchers.Main) { for (i in 0..3) tvCells[i].text = "Field ${i + 1}: ${cells[i]}" }
    }

    private fun runThrottleAdaptation060() {
        if (!isConnectedToEcu) { log("Connect first!"); return }
        scope.launch(Dispatchers.IO) {
            val resp = sendKwp2000Payload(byteArrayOf(0x31, 0x60, 0x00))
            log("TBA 060 resp: ${resp.toHex()}")
        }
    }

    private suspend fun sendKwp2000Payload(payload: ByteArray): ByteArray {
        val hdr = byteArrayOf((0x80 or payload.size).toByte(), 0x10, 0xF1.toByte()) + payload
        val cs = (hdr.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        return sendAndReceive(hdr + cs, 300)
    }

    private suspend fun sendAndReceive(packet: ByteArray, timeoutMs: Int): ByteArray {
        val port = usbPort ?: return byteArrayOf()
        return try {
            port.read(ByteArray(64), 10)
            port.write(packet, 100)
            delay(40)
            val buf = ByteArray(256)
            val total = mutableListOf<Byte>()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                val r = port.read(buf, 50)
                if (r > 0) { for (i in 0 until r) total.add(buf[i]) } else if (total.size > packet.size) break
            }
            val all = total.toByteArray()
            if (all.size > packet.size) all.sliceArray(packet.size until all.size) else byteArrayOf()
        } catch (e: Exception) { byteArrayOf() }
    }

    private fun log(msg: String) { scope.launch(Dispatchers.Main) { tvOutput.append("$msg\n") } }
    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        usbPort?.close()
        unregisterReceiver(usbReceiver)
    }
}
