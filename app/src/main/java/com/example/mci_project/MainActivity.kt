package com.example.mci_project

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val store by lazy { ScheduleStore(this) }
    private val weather by lazy { LifeWeather(this) }
    private val weatherWorker = Executors.newSingleThreadExecutor()
    private var weatherTitle: TextView? = null
    private var weatherStatus: TextView? = null
    private var weatherLoading = false
    private var weatherFailed = false
    private var guideId: String? = null
    private var guideStep = 0
    private var managing = false
    private var wasExact = false
    private var voice: TextToSpeech? = null
    private var voiceReady = false
    private var pendingSpeech: String? = null
    private var speechStatus: TextView? = null
    private var activeUtterance: String? = null
    private var auth: FirebaseAuth? = null
    private var sessionUid: String? = null
    private var elderUid: String? = null
    private var shared: FirebaseLifeSchedules? = null
    private var subscription: ListenerRegistration? = null
    private var connectionGeneration = 0
    private var sharedSchedules: List<LifeSchedule>? = null
    private var sharedStatus: String? = null
    private var cloudUpdate: String? = null
    private val authListener = FirebaseAuth.AuthStateListener { connectSharedSchedules() }
    private val familyTarget: String? get() = intent.getStringExtra(FirebaseLifeSchedules.ELDER_UID)
    private val cloudOwner: String? get() = elderUid?.takeIf { it == sessionUid }
    private val isFamily: Boolean get() = elderUid != null && elderUid != sessionUid
    private val localStatus: ScheduleStore get() = cloudOwner?.let { ScheduleStore(this, it) } ?: store

    private val ink = Color.rgb(21, 43, 64)
    private val muted = Color.rgb(65, 85, 105)
    private val accent = Color.rgb(0, 87, 81)
    private val pageColor = Color.rgb(245, 247, 250)
    private val hero = Color.rgb(224, 244, 237)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) offerExactAlarmPermission() else message("알림을 받으려면 휴대폰 설정에서 알림을 허용해 주세요.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        guideId = savedInstanceState?.getString("guide") ?: intent.getStringExtra(Reminders.ID)
        guideStep = savedInstanceState?.getInt("step") ?: 0
        managing = savedInstanceState?.getBoolean("managing") ?: false
        wasExact = Reminders.canBeExact(this)
        if (familyTarget != null) managing = true
        auth = FirebaseLifeSchedules.app(this)?.let(FirebaseAuth::getInstance)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (managing || guideId != null) goHome() else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        auth?.addAuthStateListener(authListener)
    }

    override fun onResume() {
        super.onResume()
        val exact = Reminders.canBeExact(this)
        if (exact && !wasExact) {
            store.all().forEach { Reminders.schedule(this, it) }
            cloudOwner?.let { uid -> localStatus.all().forEach { Reminders.schedule(this, it, uid) } }
        }
        wasExact = exact
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        guideId = intent.getStringExtra(Reminders.ID)
        guideStep = 0
        managing = familyTarget != null
        connectSharedSchedules()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("guide", guideId)
        outState.putInt("step", guideStep)
        outState.putBoolean("managing", managing)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        auth?.removeAuthStateListener(authListener)
        subscription?.remove()
        subscription = null
        connectionGeneration++
        stopSpeech()
        super.onStop()
    }

    override fun onDestroy() {
        voice?.shutdown()
        weatherWorker.shutdownNow()
        super.onDestroy()
    }

    private fun connectSharedSchedules() {
        subscription?.remove()
        subscription = null
        val generation = ++connectionGeneration
        val uid = auth?.currentUser?.uid
        val target = familyTarget ?: uid
        if (sessionUid != uid || elderUid != target) {
            cloudOwner?.let { previous ->
                ScheduleStore(this, previous).all().forEach { Reminders.cancel(this, it.id, previous) }
            }
            sharedSchedules = null
            cloudUpdate = null
        }
        sessionUid = uid
        elderUid = target
        val app = FirebaseLifeSchedules.app(this)
        if (uid == null || target == null || app == null) {
            shared = null
            sharedStatus = if (familyTarget != null) "가족 계정으로 로그인하고 연결을 완료해 주세요." else null
            render()
            return
        }
        val remote = FirebaseLifeSchedules(app, target)
        shared = remote
        sharedStatus = "공유 일정을 불러오는 중"
        render()
        subscription = remote.listen { items, error ->
            if (generation != connectionGeneration || isFinishing) return@listen
            if (items != null && sharedSchedules != null && items.toSet() != sharedSchedules?.toSet()) {
                cloudUpdate = "공유 일정이 갱신됐어요."
            }
            sharedSchedules = items
            sharedStatus = error
            if (items != null && cloudOwner == uid) mirrorOwnerSchedules(uid, items)
            render()
        }
    }

    private fun mirrorOwnerSchedules(uid: String, items: List<LifeSchedule>) {
        val mirror = ScheduleStore(this, uid)
        val old = mirror.all().associateBy { it.id }
        val received = items.associateBy { it.id }
        old.values.filter { received[it.id] != it }.forEach { Reminders.cancel(this, it.id, uid) }
        mirror.replace(items)
        items.filter { old[it.id] != it }.forEach { Reminders.schedule(this, it, uid) }
    }

    private fun render() {
        if (sharedSchedules == null && (shared != null || familyTarget != null)) {
            val page = screen("공유 생활 일정")
            page.addView(label(sharedStatus ?: "공유 일정을 불러오는 중", 20, muted), spacing(12))
            page.addView(button("다시 연결") { connectSharedSchedules() }, spacing(10))
            if (familyTarget != null) page.addView(button("닫기") { finish() }, spacing(8))
            return
        }
        val schedules = sharedSchedules ?: store.all()
        val guided = schedules.firstOrNull { it.id == guideId }
        when {
            guided != null -> showGuide(guided)
            managing || isFamily -> showManagement(schedules)
            else -> showHome(schedules)
        }
    }

    private fun showHome(schedules: List<LifeSchedule>) {
        val now = Calendar.getInstance()
        val today = dayKey(now)
        val list = schedules.filter { it.occursOn(now) }.sortedBy { it.minutes }
        val unfinished = list.filterNot { localStatus.isDone(it.id, today) }
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val next = unfinished.firstOrNull { it.minutes >= nowMinutes } ?: unfinished.firstOrNull()
        val page = screen("오늘의 생활 가이드")
        page.addView(label(formatDate("M월 d일 EEEE", now), 22, muted), spacing(8))
        cloudUpdate?.let { page.addView(label(it, 17, accent), spacing(6)) }

        val weatherCard = card(Color.WHITE)
        weatherCard.addView(label("오늘의 날씨", 20, bold = true, heading = true))
        weatherTitle = label("", 22, bold = true).also { weatherCard.addView(it, spacing(6)) }
        weatherStatus = label("", 16, muted).also { weatherCard.addView(it, spacing(5)) }
        val weatherActions = row()
        weatherActions.addView(button("지역 선택") { selectWeatherCity() }, equal())
        weatherActions.addView(button("날씨 새로고침") { refreshWeather(true) }, equal())
        weatherCard.addView(weatherActions, spacing(4))
        page.addView(weatherCard, spacing(18))
        refreshWeather(false)

        val nextCard = card(hero)
        val nextHeading = if (next != null && next.minutes <= nowMinutes) "지금 할 일" else "다음 할 일"
        nextCard.addView(label(nextHeading, 20, accent, bold = true, heading = true))
        if (next == null) {
            nextCard.addView(label("오늘 남은 일정이 없어요", 25, bold = true), spacing(8))
        } else {
            val time = next.timeLabel()
            nextCard.addView(label(time, 22, accent), spacing(8))
            nextCard.addView(label(next.title, 30, bold = true), spacing(3))
            nextCard.addView(button("안내 시작", primary = true) { startGuide(next) }, spacing(8))
        }
        page.addView(nextCard, spacing(14))

        page.addView(label("오늘 일정", 24, bold = true, heading = true), spacing(20))
        val scheduleCard = card(Color.WHITE)
        if (list.isEmpty()) scheduleCard.addView(label("등록된 일정이 없어요", 19, muted))
        list.forEach { item ->
            val done = if (localStatus.isDone(item.id, today)) " · 확인 완료" else ""
            scheduleCard.addView(label("${item.timeLabel()}  ${item.title}$done", 20), spacing(5))
        }
        page.addView(scheduleCard, spacing(10))
        page.addView(button("일정 관리", primary = true) { managing = true; render() }, spacing(10))
    }

    private fun showManagement(schedules: List<LifeSchedule>) {
        val page = screen(if (isFamily) "가족 생활 일정 관리" else "생활 일정 관리")
        page.addView(button(if (isFamily) "닫기" else "오늘 화면으로") {
            if (isFamily) finish() else goHome()
        }, spacing(8))
        cloudUpdate?.let { page.addView(label(it, 17, accent), spacing(6)) }
        page.addView(button("새 일정 추가", primary = true) { editSchedule(null) }, spacing(10))
        if (schedules.isEmpty()) page.addView(label("첫 일정을 추가해 주세요.", 20, muted), spacing(18))
        schedules.sortedWith(compareBy<LifeSchedule> { it.date }.thenBy { it.minutes }).forEach { item ->
            val panel = card(Color.WHITE)
            panel.addView(label(item.title, 23, bold = true, heading = true))
            panel.addView(label("${item.date} · ${item.timeLabel()} · ${item.repeat.label}", 17, muted), spacing(4))
            panel.addView(label(if (item.reminder && !localStatus.isPaused(item.id)) "알림 켜짐" else "알림 꺼짐", 17, muted), spacing(4))
            val actions = row()
            actions.addView(button("수정") { editSchedule(item) }, equal())
            actions.addView(button("삭제") { confirmDelete(item) }, equal())
            panel.addView(actions, spacing(5))
            page.addView(panel, spacing(12))
        }
        if (!isFamily && schedules.any { it.reminder } && (!Reminders.canNotify(this) || !Reminders.canBeExact(this))) {
            page.addView(button("알림 권한 설정") { checkReminderPermissions() }, spacing(12))
        }
    }

    private fun editSchedule(existing: LifeSchedule?) {
        val form = column().apply { setPadding(dp(20), dp(4), dp(20), dp(12)) }
        val title = EditText(this).apply {
            hint = "예: 산책"
            setSingleLine(true)
            setText(existing?.title.orEmpty())
        }
        form.addView(label("일정 이름", 17, bold = true))
        form.addView(title)
        var day = existing?.date?.let(::dayFromKey) ?: Calendar.getInstance()
        var minutes = existing?.minutes ?: Calendar.getInstance().apply { add(Calendar.MINUTE, 10) }.let {
            if (dayKey(it) != dayKey(day)) day = it
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        val dateButton = button(formatDate("yyyy년 M월 d일", day)) {}
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                day = Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0) }
                dateButton.text = formatDate("yyyy년 M월 d일", day)
            }, day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH)).show()
        }
        val timeButton = button(timeLabel(minutes)) {}
        timeButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                minutes = h * 60 + m
                timeButton.text = timeLabel(minutes)
            }, minutes / 60, minutes % 60, true).show()
        }
        form.addView(dateButton, spacing(6))
        form.addView(timeButton)
        form.addView(label("반복", 17, bold = true), spacing(6))
        val repeat = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val repeatChoices = Repeat.entries.associateWith { option ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = option.label
                textSize = 17f
                minimumHeight = dp(48)
                repeat.addView(this, RadioGroup.LayoutParams(0, -2, 1f))
            }.id
        }
        repeat.check(repeatChoices.getValue(existing?.repeat ?: Repeat.ONCE))
        form.addView(repeat)
        form.addView(label("단계 안내 · 한 줄에 하나씩", 17, bold = true), spacing(6))
        val steps = EditText(this).apply {
            hint = "겉옷을 입어요\n휴대폰을 챙겨요"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(existing?.steps?.joinToString("\n").orEmpty())
        }
        form.addView(steps)
        val reminder = Switch(this).apply {
            text = "정해진 시간에 알림"
            isChecked = existing?.let { it.reminder && !localStatus.isPaused(it.id) } ?: false
            minimumHeight = dp(48)
        }
        form.addView(reminder, spacing(8))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "일정 추가" else "일정 수정")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = title.text.toString().trim()
                if (name.isEmpty() || name.length > 60) {
                    title.error = "일정 이름을 1~60자로 입력해 주세요."
                    return@setOnClickListener
                }
                val instructions = steps.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                if (instructions.size > 10 || instructions.any { it.length > 120 }) {
                    steps.error = "안내는 최대 10단계이며, 단계별로 120자까지 입력할 수 있어요."
                    return@setOnClickListener
                }
                val saved = LifeSchedule(
                    existing?.id ?: UUID.randomUUID().toString(), name, dayKey(day), minutes,
                    repeatChoices.entries.first { it.value == repeat.checkedRadioButtonId }.key, instructions, reminder.isChecked
                )
                if (saved.reminder && saved.nextAlarm(System.currentTimeMillis()) == null) {
                    message("알림을 받으려면 지금보다 나중 시간을 선택해 주세요.")
                    return@setOnClickListener
                }
                saveSchedule(saved)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveSchedule(saved: LifeSchedule) {
        shared?.let { remote ->
            if (saved.reminder && !isFamily) localStatus.resumeReminder(saved.id)
            remote.put(saved) { success ->
                if (success) {
                    message("공유 일정을 저장했어요.")
                    cloudOwner?.let { Reminders.schedule(this, saved, it) }
                    if (saved.reminder && !isFamily && (!Reminders.canNotify(this) || !Reminders.canBeExact(this))) {
                        checkReminderPermissions()
                    }
                } else message("공유 일정을 저장하지 못했어요. 연결을 확인해 주세요.")
            }
            return
        }
        Reminders.cancel(this, saved.id)
        store.put(saved)
        Reminders.schedule(this, saved)
        render()
        if (saved.reminder && (!Reminders.canNotify(this) || !Reminders.canBeExact(this))) checkReminderPermissions()
    }

    private fun confirmDelete(item: LifeSchedule) {
        AlertDialog.Builder(this).setTitle("일정을 삭제할까요?")
            .setMessage(item.title)
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                val remote = shared
                if (remote != null) {
                    remote.remove(item.id) { if (!it) message("공유 일정을 삭제하지 못했어요.") }
                } else {
                    Reminders.cancel(this, item.id)
                    store.remove(item.id)
                    render()
                }
            }.show()
    }

    private fun checkReminderPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!Reminders.canNotify(this)) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            offerExactAlarmPermission()
        }
    }

    private fun offerExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < 31 || Reminders.canBeExact(this)) return
        AlertDialog.Builder(this).setTitle("정해진 시간에 알림 받기")
            .setMessage("정확한 알림을 허용하면 설정한 시간에 맞춰 안내할 수 있어요. 허용하지 않으면 알림이 늦을 수 있어요.")
            .setNegativeButton("나중에", null)
            .setPositiveButton("설정 열기") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }.onFailure { message("휴대폰 설정에서 알람 및 리마인더 권한을 확인해 주세요.") }
            }.show()
    }

    private fun startGuide(item: LifeSchedule) {
        guideId = item.id
        guideStep = 0
        render()
    }

    private fun showGuide(item: LifeSchedule) {
        val steps = item.steps.ifEmpty { listOf(item.title) }
        guideStep = guideStep.coerceIn(0, steps.lastIndex)
        val page = screen(item.title)
        page.addView(label("${guideStep + 1} / ${steps.size} 단계", 21, accent, bold = true), spacing(14))
        val panel = card(hero)
        panel.addView(label(steps[guideStep], 32, bold = true, heading = true))
        page.addView(panel, spacing(12))
        page.addView(button("다시 듣기") { speak(steps[guideStep]) }, spacing(12))
        speechStatus = label("", 17, muted).also {
            it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            it.visibility = View.GONE
            page.addView(it)
        }
        val last = guideStep == steps.lastIndex
        page.addView(button(if (last) "확인했어요" else "다음 단계", primary = true) {
            stopSpeech()
            if (last) {
                localStatus.markDone(item.id, dayKey(Calendar.getInstance()))
                Reminders.dismiss(this, item.id, cloudOwner)
                goHome()
            } else {
                guideStep++
                render()
            }
        }, spacing(8))
        if (guideStep > 0) page.addView(button("이전 단계") {
            stopSpeech()
            guideStep--
            render()
        }, spacing(8))
        page.addView(button("오늘 화면으로") { goHome() }, spacing(8))
    }

    private fun setSpeechStatus(text: String) {
        speechStatus?.apply { this.text = text; visibility = View.VISIBLE }
    }

    private fun stopSpeech() {
        pendingSpeech = null
        activeUtterance = null
        voice?.stop()
    }

    private fun speak(text: String) {
        if (voiceReady) {
            val id = UUID.randomUUID().toString()
            activeUtterance = id
            setSpeechStatus("음성 안내 준비 중")
            if (voice?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
                setSpeechStatus("음성 안내를 시작할 수 없어요.")
            }
            return
        }
        pendingSpeech = text
        setSpeechStatus("음성 안내 준비 중")
        if (voice != null) return
        voice = TextToSpeech(this) { status ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                voiceReady = status == TextToSpeech.SUCCESS &&
                    (voice?.setLanguage(Locale.KOREA) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
                if (voiceReady) {
                    voice?.setSpeechRate(0.9f)
                    voice?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = report(utteranceId, "음성 안내를 읽고 있어요.")
                        override fun onDone(utteranceId: String?) = report(utteranceId, "음성 안내를 읽었어요.")
                        @Deprecated("Required for Android speech engines using the older callback")
                        override fun onError(utteranceId: String?) =
                            report(utteranceId, "음성 재생에 실패했어요. 한국어 음성 설정을 확인해 주세요.")

                        private fun report(id: String?, text: String) {
                            runOnUiThread {
                                if (id != null && activeUtterance == id) setSpeechStatus(text)
                            }
                        }
                    })
                    pendingSpeech?.let(::speak)
                } else {
                    voice?.shutdown()
                    voice = null
                    if (pendingSpeech != null) {
                        setSpeechStatus("한국어 음성 데이터가 필요해요. 휴대폰의 텍스트 음성 변환 설정을 확인해 주세요.")
                    }
                }
                pendingSpeech = null
            }
        }
    }

    private fun goHome() {
        stopSpeech()
        guideId = null
        managing = false
        render()
    }

    private fun selectWeatherCity() {
        val cities = WeatherCity.entries
        AlertDialog.Builder(this).setTitle("날씨 지역 선택")
            .setSingleChoiceItems(cities.map { it.label }.toTypedArray(), weather.city.ordinal) { dialog, which ->
                weather.city = cities[which]
                weatherFailed = false
                dialog.dismiss()
                refreshWeather(true)
            }.setNegativeButton("닫기", null).show()
    }

    private fun refreshWeather(force: Boolean) {
        val city = weather.city
        val cached = weather.cached(city)
        showWeatherState()
        if (weatherLoading || (!force && (weatherFailed ||
            (cached != null && System.currentTimeMillis() - cached.fetchedAt < 15 * 60_000L)))) return
        weatherLoading = true
        weatherFailed = false
        showWeatherState()
        weatherWorker.execute {
            val result = runCatching { weather.fetch(city) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                weatherLoading = false
                if (weather.city != city) {
                    refreshWeather(true)
                } else {
                    weatherFailed = result.isFailure
                    showWeatherState()
                }
            }
        }
    }

    private fun showWeatherState() {
        val city = weather.city
        val cached = weather.cached(city)
        weatherTitle?.text = if (cached == null) city.label else "${city.label}  ${cached.summary}"
        val timestamp = cached?.let {
            formatDate("M/d HH:mm", Calendar.getInstance().apply { timeInMillis = it.fetchedAt })
        }
        weatherStatus?.text = when {
            weatherLoading -> "날씨 정보를 불러오는 중"
            weatherFailed && timestamp != null -> "갱신 실패 · 마지막 조회 $timestamp"
            weatherFailed -> "날씨를 불러오지 못했어요"
            timestamp != null -> "마지막 조회 $timestamp · Open-Meteo"
            else -> "아직 조회한 날씨가 없어요"
        }
    }

    private fun screen(title: String): LinearLayout {
        weatherTitle = null
        weatherStatus = null
        speechStatus = null
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageColor)
            isFillViewport = true
        }
        val page = column()
        scroll.addView(page)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            page.setPadding(bars.left + dp(20), bars.top + dp(20), bars.right + dp(20), bars.bottom + dp(24))
            insets
        }
        setContentView(scroll)
        WindowCompat.getInsetsController(window, scroll).isAppearanceLightStatusBars = true
        page.addView(label(title, 30, bold = true, heading = true))
        return page
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun timeLabel(minutes: Int): String = String.format(Locale.KOREA, "%02d:%02d", minutes / 60, minutes % 60)

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun equal() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }

    private fun card(fill: Int) = column().apply {
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(18).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun button(text: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            textSize = 17f
            isAllCaps = false
            minimumHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(if (primary) accent else hero)
            setTextColor(if (primary) Color.WHITE else accent)
            setOnClickListener { action() }
        }

    private fun label(text: String, size: Int, color: Int = ink, bold: Boolean = false, heading: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            if (heading) ViewCompat.setAccessibilityHeading(this, true)
        }

    private fun spacing(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
