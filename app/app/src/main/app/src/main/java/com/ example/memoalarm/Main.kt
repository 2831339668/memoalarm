package com.example.memoalarm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class AlarmItem(
    val id: Long, val hour: Int, val minute: Int,
    val label: String, val repeatDaily: Boolean, val enabled: Boolean = true
)

class AlarmStore(context: Context) {
    private val sp = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    fun load(): List<AlarmItem> {
        val raw = sp.getString("data", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AlarmItem(o.getLong("id"), o.getInt("hour"), o.getInt("minute"),
                    o.getString("label"), o.getBoolean("repeat"), o.getBoolean("enabled"))
            }
        } catch (e: Exception) { emptyList() }
    }
    fun save(items: List<AlarmItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("hour", it.hour); put("minute", it.minute)
                put("label", it.label); put("repeat", it.repeatDaily); put("enabled", it.enabled)
            })
        }
        sp.edit().putString("data", arr.toString()).apply()
    }
    fun add(item: AlarmItem) { val l = load().toMutableList(); l.add(item); save(l) }
    fun delete(id: Long) { save(load().filterNot { it.id == id }) }
    fun find(id: Long) = load().firstOrNull { it.id == id }
    fun setEnabled(id: Long, e: Boolean) { save(load().map { if (it.id == id) it.copy(enabled = e) else it }) }
}

object AlarmScheduler {
    fun schedule(context: Context, item: AlarmItem) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("id", item.id); putExtra("label", item.label)
        }
        val pi = PendingIntent.getBroadcast(context, item.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, item.hour); set(Calendar.MINUTE, item.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, pi), pi)
    }
    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, id.toInt(),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        val label = intent.getStringExtra("label") ?: "提醒时间到"
        showNotification(context, id, label)
        try {
            context.startActivity(Intent(context, AlarmActivity::class.java).apply {
                putExtra("label", label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        } catch (_: Exception) {}
        val store = AlarmStore(context)
        val item = store.find(id)
        if (item != null) {
            if (item.repeatDaily) AlarmScheduler.schedule(context, item)
            else store.setEnabled(id, false)
        }
    }
    private fun showNotification(context: Context, id: Long, label: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val chId = "alarm_channel"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(chId, "全屏提醒",
                NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val pi = PendingIntent.getActivity(context, id.toInt(),
            Intent(context, AlarmActivity::class.java).apply {
                putExtra("label", label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, chId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $label")
            .setContentText("点击关闭")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()
        nm.notify(id.toInt(), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmStore(context).load().filter { it.enabled }.forEach {
                AlarmScheduler.schedule(context, it)
            }
        }
    }
}

class AlarmActivity : AppCompatActivity() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        findViewById<TextView>(R.id.alarmLabel).text = intent.getStringExtra("label") ?: "提醒"
        findViewById<TextView>(R.id.alarmTime).text =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        findViewById<Button>(R.id.dismissBtn).setOnClickListener { finish() }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            ringtone?.play()
        } catch (_: Exception) {}
        try {
            vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26)
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
            else @Suppress("DEPRECATION") vibrator?.vibrate(longArrayOf(0, 500, 500), 0)
        } catch (_: Exception) {}
    }
    override fun onDestroy() {
        super.onDestroy()
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var store: AlarmStore
    private lateinit var listView: ListView
    private val items = mutableListOf<AlarmItem>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = AlarmStore(this)
        listView = findViewById(R.id.list)
        findViewById<Button>(R.id.addBtn).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.permBtn).setOnClickListener { openPermissionPages() }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val item = items[pos]
            AlertDialog.Builder(this).setTitle("删除？").setMessage(item.label)
                .setPositiveButton("删除") { _, _ ->
                    AlarmScheduler.cancel(this, item.id); store.delete(item.id); refresh()
                }.setNegativeButton("取消", null).show()
            true
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        refresh()
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        items.clear(); items.addAll(store.load().sortedBy { it.hour * 60 + it.minute })
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            items.map {
                val rep = if (it.repeatDaily) "（每天）" else ""
                String.format("%02d:%02d %s\n%s", it.hour, it.minute, rep, it.label)
            })
    }
    private fun showAddDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_add, null)
        val timeBtn = v.findViewById<Button>(R.id.timeBtn)
        val labelEt = v.findViewById<EditText>(R.id.labelEt)
        val repeatCb = v.findViewById<CheckBox>(R.id.repeatCb)
        val cal = Calendar.getInstance()
        var hour = cal.get(Calendar.HOUR_OF_DAY)
        var minute = cal.get(Calendar.MINUTE) + 1
        if (minute >= 60) { minute = 0; hour = (hour + 1) % 24 }
        timeBtn.text = String.format("%02d:%02d", hour, minute)
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                hour = h; minute = m
                timeBtn.text = String.format("%02d:%02d", h, m)
            }, hour, minute, true).show()
        }
        AlertDialog.Builder(this).setTitle("新建提醒").setView(v)
            .setPositiveButton("保存") { _, _ ->
                val item = AlarmItem(System.currentTimeMillis(), hour, minute,
                    labelEt.text.toString().ifBlank { "提醒" }, repeatCb.isChecked)
                store.add(item); AlarmScheduler.schedule(this, item); refresh()
            }.setNegativeButton("取消", null).show()
    }
    private fun openPermissionPages() {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
        Toast.makeText(this, "请开：通知、全屏通知、后台弹出界面、自启动、省电无限制",
            Toast.LENGTH_LONG).show()
    }
}
