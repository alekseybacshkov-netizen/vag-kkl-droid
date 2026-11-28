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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.vag.kkldroid.USB_PERMISSION"
    private var usbPort: UsbSerialPort? = null
    private var isConnectedToEcu = false
    private var activeProtocol = "" // "KWP2000" or "KWP1281"
    private var kwp1281Seq = 0x01

    private var keepAliveJob: Job? = null
    private var pollingJob: Job? = null
    private val isBusy = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvStatus: TextView
    private lateinit var tvEcuInfo: TextView
    private lateinit var tvOutput: TextView
    private lateinit var spinnerModule: Spinner
    private lateinit var etGroup: EditText
    private lateinit var tvCells: List<TextView>

    private val vagDtcDict = mapOf(
        "00515" to "G40 - Datchik Holla (DPRV)",
        "00525" to "G39 - Lyambda-zond",
        "00532" to "Napryazhenie pitaniya B+",
        "00561" to "Adaptaciya smesi",
        "00588" to "N95 - Piropatron podushki voditelya",
        "00771" to "G - Datchik urovnya topliva",
        "01176" to "Klyuch: slishkom slabyy signal (Immo)",
        "01314" to "Blok upravleniya dvigatelem: net svyazi",
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
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modules.map { it.first }
            )
        }
        root.addView(spinnerModule)

        val rowConn = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnConnect = Button(this).apply { text = "CONNECT ECU" }
        val btnDisconnect = Button(this).apply { text = "STOP / CLOSE" }
        rowConn.addView(btnConnect, LinearLayout.LayoutParams(0, -2, 2f))
        rowConn.addView(btnDisconnect, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(rowConn)

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
            text = "Ready (CH340 Safe Mode)...\n"
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
        btnDisconnect.setOnClickListener { disconnectEcu() }
        btnReadDtc.setOnClickListener { readFaultCodes() }
        btnClearDtc.setOnClickListener { clearFaultCodes() }
        btnReadGroup.setOnClickListener { toggleGroupPolling() }
        btnBasic060.setOnClickListener { runThrottleAdaptation060() }
    }

    private fun requestUsbConnection() {
        try {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            if (drivers.isEmpty()) {
                log("CH340/FTDI not found! Check OTG cable.")
                return
            }
            val driver = drivers.first()
            if (!manager.hasPermission(driver.device)) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                manager.requestPermission(
                    driver.device,
                    PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                )
            } else {
                connectUsbAndEcu()
            }
        } catch (e: Exception) {
            log("USB Req Err: ${e.message}")
        }
    }

    private fun disconnectEcu() {
        pollingJob?.cancel()
        keepAliveJob?.cancel()
        isConnectedToEcu = false
        activeProtocol = ""
        isBusy.set(false)
        try {
            usbPort?.close()
        } catch (_: Exception) {
        }
        usbPort = null
        tvStatus.text = "USB: Disconnected"
        tvStatus.setTextColor(Color.parseColor("#FF5252"))
        tvEcuInfo.text = "ECU: none"
        log("Disconnected.")
    }

    private fun connectUsbAndEcu() {
        pollingJob?.cancel()
        keepAliveJob?.cancel()
        isConnectedToEcu = false
        isBusy.set(false)

        val selectedPos = spinnerModule.selectedItemPosition
        val moduleName = modules[selectedPos].first
        val address = modules[selectedPos].second

        scope.launch(Dispatchers.IO) {
            try {
                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
                if (driver == null) {
                    log("Driver lost.")
                    return@launch
                }
                val connection = manager.openDevice(driver.device)
                if (connection == null) {
                    log("Cannot open USB device.")
                    return@launch
                }

                try { usbPort?.close() } catch (_: Exception) {}
                val port = driver.ports.first()
                port.open(connection)
                usbPort = port

                withContext(Dispatchers.Main) {
                    tvStatus.text = "USB: Connected (${driver.device.productName ?: "CH340"})"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                }

                log("Init 0x${address.toString(16).uppercase()} ($moduleName)...")

                val sync = performSafe5BaudInit(port, address)
                log("Sync RX: ${if (sync.isEmpty()) "EMPTY" else sync.toHex()}")

                val idx55 = sync.indexOf(0x55.toByte())
                if (idx55 == -1 || sync.size < idx55 + 3) {
                    log("No valid 0x55 sync from 0x${address.toString(16)}. Check ignition ON.")
                    return@launch
                }

                val kw1 = sync[idx55 + 1].toInt() and 0xFF
                val kw2 = sync[idx55 + 2].toInt() and 0xFF
                log("Keywords: %02X %02X".format(kw1, kw2))

                delay(30)
                val invKw2 = (kw2 xor 0xFF).toByte()
                port.write(byteArrayOf(invKw2), 100)
                delay(15)
                port.read(ByteArray(32), 30)

                if (address == 0x01 && (kw2 == 0x8F || kw1 == 0xEF || kw1 == 0xE9)) {
                    initKwp2000Session(port, address)
                } else {
                    initKwp1281Session(port, address)
                }

            } catch (e: Exception) {
                log("Connect Error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
        private suspend fun performSafe5BaudInit(port: UsbSerialPort, address: Int): ByteArray {
        port.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        delay(300)

        port.setParameters(300, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        val bits = IntArray(10)
        bits.set(0, 0)
        for (i in 0..7) {
            bits.set(i + 1, (address shr i) and 1)
        }
        bits.set(9, 1)

        val lowBurst = ByteArray(6) { 0x00 }
        for (bit in bits) {
            if (bit == 0) {
                port.write(lowBurst, 250)
                delay(185)
            } else {
                delay(200)
            }
        }

        port.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        val collected = mutableListOf<Byte>()
        val buf = ByteArray(64)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 700) {
            val n = try { port.read(buf, 40) } catch (_: Exception) { 0 }
            if (n > 0) {
                for (i in 0 until n) collected.add(buf[i])
                val idx55 = collected.indexOf(0x55.toByte())
                if (idx55 != -1 && collected.size >= idx55 + 3) break
            }
        }
        return collected.toByteArray()
    }

    // ==================== KWP2000 (1.8T AWM Engine) ====================

    private suspend fun initKwp2000Session(port: UsbSerialPort, address: Int) {
        val buf = ByteArray(32)
        val n = try { port.read(buf, 150) } catch (_: Exception) { 0 }
        val invAddr = if (n > 0) buf.copyOf(n).toHex() else "none"
        log("KWP2000 InvAddr RX: $invAddr")

        delay(50)
        val startResp = sendKwp2000Payload(byteArrayOf(0x10, 0x89.toByte()))
        log("StartSession RX: ${startResp.toHex()}")

        activeProtocol = "KWP2000"
        isConnectedToEcu = true

        val idResp = sendKwp2000Payload(byteArrayOf(0x1A, 0x9B.toByte()))
        val ascii = extractPrintableAscii(idResp)
        withContext(Dispatchers.Main) {
            tvEcuInfo.text = "ECU (KWP2000): ${ascii.ifEmpty { "Connected 0x01" }}"
        }
        log("Connected KWP2000! ID: $ascii")

        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnectedToEcu) {
                delay(2000)
                if (!isBusy.get() && pollingJob?.isActive != true) {
                    sendKwp2000Payload(byteArrayOf(0x3E))
                }
            }
        }
    }

    private suspend fun sendKwp2000Payload(payload: ByteArray): ByteArray {
        val port = usbPort ?: return byteArrayOf()
        while (!isBusy.compareAndSet(false, true)) {
            delay(10)
        }
        return try {
            val hdr = byteArrayOf((0x80 or payload.size).toByte(), 0x10, 0xF1.toByte()) + payload
            val cs = (hdr.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
            val packet = hdr + cs

            port.read(ByteArray(128), 5)
            port.write(packet, 150)
            delay(35)

            val buf = ByteArray(256)
            val total = mutableListOf<Byte>()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 400) {
                val r = try { port.read(buf, 40) } catch (_: Exception) { 0 }
                if (r > 0) {
                    for (i in 0 until r) total.add(buf[i])
                } else if (total.size > packet.size + 3) {
                    break
                }
            }
            stripEcho(packet, total.toByteArray())
        } catch (e: Exception) {
            byteArrayOf()
        } finally {
            isBusy.set(false)
        }
    }
        // ==================== KWP1281 (AT, ABS, Airbags, Instruments, Comfort) ====================

    private suspend fun initKwp1281Session(port: UsbSerialPort, address: Int) {
        activeProtocol = "KWP1281"
        isConnectedToEcu = true
        kwp1281Seq = 0x01

        val infoParts = mutableListOf<String>()
        for (attempt in 0 until 8) {
            val block = readKwp1281Block(port) ?: break
            val title = block.first
            val data = block.second
            if (title == 0x09) {
                break
            } else if (title == 0xF6 || title == 0x00) {
                val text = extractPrintableAscii(data)
                if (text.isNotEmpty()) {
                    infoParts.add(text)
                    log("ID Block: $text")
                }
            }
            delay(15)
            sendKwp1281Block(port, 0x09, byteArrayOf())
        }

        val fullId = infoParts.joinToString(" | ").ifEmpty { "Module 0x${address.toString(16).uppercase()}" }
        withContext(Dispatchers.Main) {
            tvEcuInfo.text = "ECU (KWP1281): $fullId"
        }
        log("Connected KWP1281!")

        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnectedToEcu) {
                delay(600)
                if (!isBusy.get() && pollingJob?.isActive != true) {
                    if (isBusy.compareAndSet(false, true)) {
                        try {
                            sendKwp1281Block(port, 0x09, byteArrayOf())
                            readKwp1281Block(port)
                        } catch (_: Exception) {
                        } finally {
                            isBusy.set(false)
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendKwp1281Block(port: UsbSerialPort, title: Int, data: ByteArray): Boolean {
        return try {
            val len = 3 + data.size
            val frame = ByteArray(len + 1)
            frame.set(0, len.toByte())
            frame.set(1, kwp1281Seq.toByte())
            frame.set(2, title.toByte())
            for (i in data.indices) frame.set(3 + i, data[i])
            frame.set(len, 0x03)

            kwp1281Seq = (kwp1281Seq + 1) and 0xFF
            if (kwp1281Seq == 0) kwp1281Seq = 1

            port.read(ByteArray(64), 5)
            val rx = ByteArray(16)
            for (i in frame.indices) {
                port.write(byteArrayOf(frame[i]), 50)
                delay(4)
                val expectBytes = if (i < frame.lastIndex) 2 else 1
                var got = 0
                val t0 = System.currentTimeMillis()
                while (got < expectBytes && System.currentTimeMillis() - t0 < 80) {
                    val r = port.read(rx, 20)
                    if (r > 0) got += r
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun readKwp1281Block(port: UsbSerialPort): Pair<Int, ByteArray>? {
        return try {
            val raw = mutableListOf<Byte>()
            val buf = ByteArray(16)

            var blockLen = -1
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 500) {
                val r = port.read(buf, 30)
                if (r > 0) {
                    for (i in 0 until r) {
                        val b = buf[i]
                        raw.add(b)
                        if (blockLen == -1) {
                            blockLen = b.toInt() and 0xFF
                            if (blockLen < 3 || blockLen > 64) return null
                        }
                        if (raw.size <= blockLen) {
                            val comp = ((b.toInt() and 0xFF) xor 0xFF).toByte()
                            port.write(byteArrayOf(comp), 40)
                            delay(2)
                            port.read(ByteArray(8), 10)
                        }
                    }
                    if (blockLen != -1 && raw.size >= blockLen + 1) break
                }
            }

            if (raw.size < 4) return null
            kwp1281Seq = ((raw.get(1).toInt() and 0xFF) + 1) and 0xFF
            val title = raw.get(2).toInt() and 0xFF
            val payload = if (raw.size > 4) raw.subList(3, raw.size - 1).toByteArray() else byteArrayOf()
            Pair(title, payload)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== DTC & MEASURING BLOCKS ====================

    private fun readFaultCodes() {
        if (!isConnectedToEcu) {
            log("Press Connect first!")
            return
        }
        pollingJob?.cancel()
        scope.launch(Dispatchers.IO) {
            if (activeProtocol == "KWP2000") {
                log("Reading KWP2000 DTCs...")
                val resp = sendKwp2000Payload(byteArrayOf(0x18, 0x00, 0xFF.toByte(), 0x00))
                log("DTC Raw: ${resp.toHex()}")
                val idx = resp.indexOf(0x58.toByte())
                if (idx != -1 && resp.size > idx + 1) {
                    val count = resp[idx + 1].toInt() and 0xFF
                    if (count == 0) {
                        log("0 DTCs found.")
                    } else {
                        var pos = idx + 2
                        for (i in 0 until count) {
                            if (pos + 2 < resp.size) {
                                val code = ((resp[pos].toInt() and 0xFF) shl 8) or (resp[pos + 1].toInt() and 0xFF)
                                val st = resp[pos + 2].toInt() and 0xFF
                                val cStr = code.toString().padStart(5, '0')
                                val hStr = code.toString(16).uppercase().padStart(4, '0')
                                val flag = if ((st and 0x80) != 0) "[Interm]" else "[Static]"
                                log("- $cStr ($hStr) $flag: ${vagDtcDict[cStr] ?: "VAG DTC"}")
                                pos += 3
                            }
                        }
                    }
                } else {
                    log("No 0x58 response.")
                }
            } else if (activeProtocol == "KWP1281") {
                val port = usbPort ?: return@launch
                while (!isBusy.compareAndSet(false, true)) delay(10)
                try {
                    log("Reading KWP1281 DTCs...")
                    sendKwp1281Block(port, 0x07, byteArrayOf())
                    var foundAny = false
                    for (i in 0 until 10) {
                        val block = readKwp1281Block(port) ?: break
                        if (block.first == 0x09) break
                        if (block.first == 0xFC) {
                            val d = block.second
                            var p = 0
                            while (p + 2 < d.size) {
                                val code = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
                                val st = d[p + 2].toInt() and 0xFF
                                if (code == 0xFFFF) {
                                    log("0 DTCs found.")
                                } else {
                                    foundAny = true
                                    val cStr = code.toString().padStart(5, '0')
                                    val flag = if ((st and 0x80) != 0) "[Interm]" else "[Static]"
                                    log("- $cStr $flag: ${vagDtcDict[cStr] ?: "VAG DTC"}")
                                }
                                p += 3
                            }
                        }
                        delay(15)
                        sendKwp1281Block(port, 0x09, byteArrayOf())
                    }
                    if (!foundAny) log("DTC check finished.")
                } finally {
                    isBusy.set(false)
                }
            }
        }
    }
        private fun clearFaultCodes() {
        if (!isConnectedToEcu) {
            log("Press Connect first!")
            return
        }
        pollingJob?.cancel()
        scope.launch(Dispatchers.IO) {
            if (activeProtocol == "KWP2000") {
                val resp = sendKwp2000Payload(byteArrayOf(0x14, 0xFF.toByte(), 0x00))
                log(if (resp.contains(0x54.toByte())) "KWP2000 DTCs cleared!" else "Clear resp: ${resp.toHex()}")
            } else if (activeProtocol == "KWP1281") {
                val port = usbPort ?: return@launch
                while (!isBusy.compareAndSet(false, true)) delay(10)
                try {
                    sendKwp1281Block(port, 0x05, byteArrayOf())
                    val resp = readKwp1281Block(port)
                    log(if (resp?.first == 0x09) "KWP1281 DTCs cleared!" else "Clear resp: ${resp?.first}")
                } finally {
                    isBusy.set(false)
                }
            }
        }
    }

    private fun toggleGroupPolling() {
        if (pollingJob?.isActive == true) {
            pollingJob?.cancel()
            log("Group polling stopped.")
            return
        }
        if (!isConnectedToEcu) {
            log("Connect first!")
            return
        }
        val g = (etGroup.text.toString().toIntOrNull() ?: 1).coerceIn(0, 255)
        log("Polling Group ${g.toString().padStart(3, '0')}...")

        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnectedToEcu) {
                if (activeProtocol == "KWP2000") {
                    val resp = sendKwp2000Payload(byteArrayOf(0x21, g.toByte()))
                    val idx = resp.indexOf(0x61.toByte())
                    if (idx != -1 && resp.size > idx + 2) {
                        parseVagTriplets(resp.sliceArray((idx + 2) until resp.size))
                    }
                    delay(200)
                } else if (activeProtocol == "KWP1281") {
                    val port = usbPort ?: break
                    if (isBusy.compareAndSet(false, true)) {
                        try {
                            sendKwp1281Block(port, 0x29, byteArrayOf(g.toByte()))
                            val block = readKwp1281Block(port)
                            if (block != null && block.first == 0xE7) {
                                parseVagTriplets(block.second)
                            }
                        } finally {
                            isBusy.set(false)
                        }
                    }
                    delay(250)
                }
            }
        }
    }

    private suspend fun parseVagTriplets(data: ByteArray) {
        val cells = Array(4) { "---" }
        for (i in 0 until 4) {
            val off = i * 3
            if (off + 2 < data.size) {
                val f = data[off].toInt() and 0xFF
                val a = data[off + 1].toInt() and 0xFF
                val b = data[off + 2].toInt() and 0xFF
                cells[i] = decodeVagFormula(f, a, b)
            }
        }
        withContext(Dispatchers.Main) {
            for (i in 0..3) tvCells[i].text = "Field ${i + 1}: ${cells[i]}"
        }
    }

    private fun decodeVagFormula(f: Int, a: Int, b: Int): String {
        return when (f) {
            0x01 -> String.format(Locale.US, "%.0f RPM", a * b * 0.2)
            0x02 -> String.format(Locale.US, "%.1f %%", a * b * 0.002)
            0x03 -> String.format(Locale.US, "%.1f deg", a * b * 0.002)
            0x04 -> String.format(Locale.US, "%.1f ATDC", (b - 127) * a * 0.01)
            0x05 -> String.format(Locale.US, "%.1f C", a * (b - 100) * 0.1)
            0x06 -> String.format(Locale.US, "%.2f V", a * b * 0.001)
            0x07 -> String.format(Locale.US, "%.0f km/h", a * b * 0.01)
            0x08 -> String.format(Locale.US, "%.1f", a * b * 0.1)
            0x09 -> String.format(Locale.US, "%.1f deg", (b - 127) * a * 0.02)
            0x0B -> String.format(Locale.US, "%.4f", a * (b - 128) * 0.0001 + 1.0)
            0x0D -> String.format(Locale.US, "%.2f mm", (b - 127) * a * 0.001)
            0x0E -> String.format(Locale.US, "%.1f bar", a * b * 0.005)
            0x0F -> String.format(Locale.US, "%.2f ms", a * b * 0.01)
            0x10 -> b.toString(2).padStart(8, '0')
            0x12 -> String.format(Locale.US, "%.0f mbar", a * b * 0.04)
            0x13 -> String.format(Locale.US, "%.1f l", a * b * 0.01)
            0x14 -> String.format(Locale.US, "%.1f %%", a * (b - 128) * 0.01)
            0x15 -> String.format(Locale.US, "%.2f V", a * b * 0.001)
            0x16 -> String.format(Locale.US, "%.2f ms", a * b * 0.001)
            0x17 -> String.format(Locale.US, "%.1f %%", a * b * 0.0039)
            0x18 -> String.format(Locale.US, "%.2f A", a * b * 0.001)
            0x19 -> String.format(Locale.US, "%.2f g/s", (b * 1.421) + (a / 182.0))
            0x1A -> String.format(Locale.US, "%.1f C", (b - a).toDouble())
            0x1B -> String.format(Locale.US, "%.1f BTDC", (b - 128) * a * 0.01)
            0x1E -> String.format(Locale.US, "%.1f deg", a * b * 0.002)
            0x21 -> String.format(Locale.US, "%.1f %%", if (a == 0) b * 1.0 else (b * 100.0) / a)
            0x22 -> String.format(Locale.US, "%.1f kW", (b - 128) * a * 0.01)
            0x25 -> "State: $b"
            0x2B -> String.format(Locale.US, "%.2f V", a * b * 0.0001)
            0x31 -> String.format(Locale.US, "%.1f mg/str", a * b * 0.025)
            0x33 -> String.format(Locale.US, "%.1f mg/str", (b - 128) * a * 0.004)
            0x36 -> ((a shl 8) or b).toString()
            0x3B -> String.format(Locale.US, "%.3f", (a * 256 + b) / 32768.0)
            0x3E -> String.format(Locale.US, "%.2f S", a * b * 0.256)
            0x40 -> "${a + b} Ohm"
            0x41 -> String.format(Locale.US, "%.1f mm", (b - 127) * a * 0.01)
            0x42 -> String.format(Locale.US, "%.2f V", a * b * 0.0005)
            0x43 -> String.format(Locale.US, "%.1f deg", (a * 640) + (b * 2.5) - 900.0)
            else -> "F:%02X A:$a B:$b".format(f)
        }
    }

    private fun runThrottleAdaptation060() {
        if (!isConnectedToEcu) {
            log("Connect first!")
            return
        }
        pollingJob?.cancel()
        scope.launch(Dispatchers.IO) {
            if (activeProtocol == "KWP2000") {
                val resp = sendKwp2000Payload(byteArrayOf(0x31, 0x60, 0x00))
                log("TBA 060 resp: ${resp.toHex()}")
            } else {
                log("TBA 060 supported on Engine (01) first.")
            }
        }
    }

    private fun stripEcho(sent: ByteArray, received: ByteArray): ByteArray {
        if (received.size <= sent.size) return byteArrayOf()
        var matches = true
        for (i in sent.indices) {
            if (sent[i] != received[i]) {
                matches = false
                break
            }
        }
        return if (matches) received.sliceArray(sent.size until received.size) else received
    }

    private fun extractPrintableAscii(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun log(msg: String) {
        scope.launch(Dispatchers.Main) {
            tvOutput.append("$msg\n")
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        keepAliveJob?.cancel()
        try { usbPort?.close() } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }
}
