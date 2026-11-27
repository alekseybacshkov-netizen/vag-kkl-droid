package:com.vag.kkldroid

import:android.app.PendingIntent
import:android.content.BroadcastReceiver
import:android.content.Context
import:android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
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
        "16486" to "P0102 - ДМРВ (G70): слишком низкий уровень сигнала",
        "16487" to "P0103 - ДМРВ (G70): слишком высокий уровень сигнала",
        "16514" to "P0130 - Ряд 1, зонд 1: сбой в электрической цепи",
        "16555" to "P0171 - Ряд 1, система определения параметров топливо-возд. смеси: бедная смесь",
        "16556" to "P0172 - Ряд 1, система определения параметров топливо-возд. смеси: богатая смесь",
        "16684" to "P0300 - Обнаружены пропуски воспламенения",
        "16685" to "P0301 - Цилиндр 1: обнаружены пропуски воспламенения",
        "16686" to "P0302 - Цилиндр 2: обнаружены пропуски воспламенения",
        "16687" to "P0303 - Цилиндр 3: обнаружены пропуски воспламенения",
        "16688" to "P0304 - Цилиндр 4: обнаружены пропуски воспламенения",
        "16705" to "P0321 - Датчик оборотов двигателя (G28): недостоверный сигнал",
        "16725" to "P0341 - Датчик положения распредвала (G40): недостоверный сигнал",
        "16825" to "P0441 - Система вентиляции топливного бака: неверная пропускная способность",
        "17535" to "P1127 - Ряд 1, адаптация смеси (под нагрузкой): переобогащение смеси",
        "17536" to "P1128 - Ряд 1, адаптация смеси (под нагрузкой): переобеднение смеси",
        "17705" to "P1297 - Падение давления между турбиной и дроссельной заслонкой (проверьте байпас/опрессовку)",
        "17965" to "P1557 - Регулятор давления наддува: выход из диапазона регулирования (передув)",
        "17978" to "P1570 - Блокирован блок управления двигателя (Иммобилайзер)",
        "18010" to "P1602 - Напряжение питания на кл. 30: напряжение слишком низкое (снималась клемма АКБ)"
    )

    private val modules = listOf(
        Pair("01 - Двигатель (1.8T AWM - KWP2000)", 0x01),
        Pair("02 - АКПП (KWP1281)", 0x02),
        Pair("03 - Тормозная система ABS (KWP1281)", 0x03),
        Pair("15 - Подушки безопасности Airbag (KWP1281)", 0x15),
        Pair("17 - Комбинация приборов (KWP1281)", 0x17),
        Pair("46 - Блок комфорта (KWP1281)", 0x46)
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) connectUsbAndEcu() else log("Отказано в доступе к USB.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvEcuInfo = findViewById(R.id.tvEcuInfo)
        tvOutput = findViewById(R.id.tvOutput)
        tvOutput.movementMethod = ScrollingMovementMethod()
        spinnerModule = findViewById(R.id.spinnerModule)
        etGroup = findViewById(R.id.etGroup)
        tvCells = listOf(
            findViewById(R.id.tvCell1),
            findViewById(R.id.tvCell2),
            findViewById(R.id.tvCell3),
            findViewById(R.id.tvCell4)
        )

        spinnerModule.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modules.map { it.first }
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { requestUsbConnection() }
        findViewById<Button>(R.id.btnReadDtc).setOnClickListener { readFaultCodes() }
        findViewById<Button>(R.id.btnClearDtc).setOnClickListener { clearFaultCodes() }
        findViewById<Button>(R.id.btnReadGroup).setOnClickListener { toggleGroupPolling() }
        findViewById<Button>(R.id.btnBasic060).setOnClickListener { runThrottleAdaptation060() }
    }

    private fun requestUsbConnection() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) {
            log("Адаптер CH340/FTDI не найден! Проверьте OTG и зажигание.")
            return
        }
        val driver = drivers.first()
        if (!manager.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(driver.device, pi)
        } else {
            connectUsbAndEcu()
        }
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
                    tvStatus.text = "USB: Подключен (${driver.device.productName ?: "CH340"})"
                    tvStatus.setTextColor(0xFF00E676.toInt())
                }

                val selectedAddr = modules[spinnerModule.selectedItemPosition].second
                if (selectedAddr == 0x01) {
                    initKwp2000Engine()
                } else {
                    initKwp1281Slow(selectedAddr)
                }
            } catch (e: Exception) {
                log("Ошибка порта: ${e.message}")
            }
        }
    }

    private suspend fun initKwp2000Engine() {
        val port = usbPort ?: return
        log("Запуск Fast Init (KWP2000) для блока 01 (AWM ME7.5)...")

        port.setParameters(360, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.write(byteArrayOf(0x00), 50)
        delay(25)
        port.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        val startReq = byteArrayOf(0x81.toByte(), 0x10, 0xF1.toByte(), 0x81.toByte(), 0x03)
        val resp = sendAndReceive(startReq, 400)

        if (resp.isNotEmpty() && resp.contains(0xC1.toByte())) {
            isConnectedToEcu = true
            log("УСПЕХ! Связь с Bosch ME7.5 установлена: ${resp.toHex()}")
            val idResp = sendKwp2000Payload(byteArrayOf(0x21, 0x9B.toByte()))
            val ascii = idResp.filter { it in 32..126 }.map { it.toInt().toChar() }.joinToString("")
            withContext(Dispatchers.Main) {
                tvEcuInfo.text = "ЭБУ: ${if (ascii.isNotEmpty()) ascii else "Bosch Motronic ME7.5 (AWM)"}"
            }
        } else {
            isConnectedToEcu = false
            log("Нет ответа от ЭБУ по Fast Init (ответ: ${resp.toHex()}). Пробуем 5-baud KWP1281...")
            initKwp1281Slow(0x01)
        }
    }

    private suspend fun initKwp1281Slow(address: Int) {
        val port = usbPort ?: return
        log("Попытка 5-baud инициализации адреса 0x${address.toString(16).uppercase()}...")
        port.setParameters(5, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.write(byteArrayOf(address.toByte()), 2200)
        delay(2100)
        port.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        val buf = ByteArray(32)
        val len = port.read(buf, 500)
        if (len > 0) {
            val sync = buf.copyOf(len)
            log("Получен синхроимпульс: ${sync.toHex()}")
            if (sync.size >= 3) {
                val kw2Inv = (sync.last().toInt() xor 0xFF).toByte()
                delay(30)
                port.write(byteArrayOf(kw2Inv), 100)
                isConnectedToEcu = true
                log("Блок 0x${address.toString(16)} ответил (KWP1281)!")
            }
        } else {
            log("Блок 0x${address.toString(16)} не ответил. На CH340 5-baud init может требовать повтора.")
        }
    }

    private fun readFaultCodes() {
        if (!isConnectedToEcu) {
            log("Сначала нажмите «Подключить»!")
            return
        }
        scope.launch(Dispatchers.IO) {
            log("Чтение заводских кодов ошибок VAG (18 00 FF 00)...")
            val resp = sendKwp2000Payload(byteArrayOf(0x18, 0x00, 0xFF.toByte(), 0x00))
            if (resp.isEmpty()) {
