package com.example.mci_project

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.endsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.util.Calendar
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeGuideFlowTest {
    @Test
    fun addRepeatReopenCompleteAndDeleteSchedule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ScheduleStore(context)
        val title = "검증용 산책 ${System.nanoTime()}"
        val updatedTitle = "$title 수정"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withText("일정 관리")).perform(scrollTo(), click())
                onView(withText("새 일정 추가")).perform(scrollTo(), click())
                onView(withHint("예: 산책")).perform(scrollTo(), replaceText(title))
                onView(withHint("겉옷을 입어요\n휴대폰을 챙겨요"))
                    .perform(scrollTo(), replaceText("겉옷 입기\n휴대폰 챙기기"))
                closeSoftKeyboard()
                onView(withText("매일")).perform(scrollTo(), click())
                onView(withText("저장")).perform(click())
                onView(withText(title)).check(matches(isDisplayed()))
                onView(allOf(withText("수정"), withParent(withParent(hasDescendant(withText(title))))))
                    .perform(scrollTo(), click())
                onView(withHint("예: 산책")).perform(scrollTo(), replaceText(updatedTitle))
                closeSoftKeyboard()
                onView(withText("저장")).perform(click())
                onView(withText(updatedTitle)).check(matches(isDisplayed()))
            }

            val saved = store.all().single { it.title == updatedTitle }
            assertEquals(Repeat.DAILY, saved.repeat)
            assertEquals(listOf("겉옷 입기", "휴대폰 챙기기"), saved.steps)
            assertTrue(saved.nextAlarm(System.currentTimeMillis()) != null)

            val intent = Intent(context, MainActivity::class.java).putExtra(Reminders.ID, saved.id)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                onView(withText("겉옷 입기")).check(matches(isDisplayed()))
                onView(withText("다시 듣기")).perform(scrollTo(), click())
                var voiceOutcome: String? = null
                assertTrue("The speech engine should finish or report an error", eventually(150) {
                    scenario.onActivity { activity ->
                        voiceOutcome = visibleText(activity.window.decorView).firstOrNull {
                            it == "음성 안내를 읽었어요." || it.startsWith("음성 재생에 실패") ||
                                it.startsWith("한국어 음성 데이터가 필요") || it.startsWith("음성 안내를 시작할 수 없어요")
                        }
                    }
                    voiceOutcome != null
                })
                Log.i("LifeGuideSmoke", "Voice outcome: $voiceOutcome")
                onView(withText("다음 단계")).perform(scrollTo(), click())
                onView(withText("휴대폰 챙기기")).check(matches(isDisplayed()))
                onView(withText("확인했어요")).perform(scrollTo(), click())
                assertTrue(store.isDone(saved.id, dayKey(java.util.Calendar.getInstance())))
                onView(withText("일정 관리")).perform(scrollTo(), click())
                onView(allOf(withText("삭제"), withParent(withParent(hasDescendant(withText(updatedTitle))))))
                    .perform(scrollTo(), click())
                onView(allOf(withText("삭제"), withParent(withClassName(endsWith("ButtonBarLayout")))))
                    .perform(click())
            }
            assertFalse(store.all().any { it.id == saved.id })
        } finally {
            store.all().filter { it.title == title || it.title == updatedTitle }.forEach { store.remove(it.id) }
        }
    }

    @Test
    fun notificationCanBeConfirmedOrPaused() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires notification permission on the test device", Reminders.canNotify(context))
        val store = ScheduleStore(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val date = dayKey(now)
        val id = UUID.randomUUID().toString()
        val item = LifeSchedule(id, "검증용 알림", date,
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), Repeat.DAILY, emptyList(), true)
        fun notificationPresent() = manager.activeNotifications.any { it.tag == "device:$id" }
        try {
            store.put(item)
            Reminders.fire(context, item, now.timeInMillis, null)
            assertTrue(eventually { notificationPresent() })
            ReminderReceiver().onReceive(context, Intent(Reminders.CONFIRM).putExtra(Reminders.ID, id)
                .putExtra(Reminders.DATE, date))
            assertTrue(store.isDone(id, date))
            assertTrue(eventually { !notificationPresent() })

            ReminderReceiver().onReceive(context, Intent(Reminders.PAUSE).putExtra(Reminders.ID, id))
            assertFalse(store.all().single { it.id == id }.reminder)
        } finally {
            Reminders.cancel(context, id)
            store.remove(id)
        }
    }

    private fun visibleText(view: View): Sequence<String> = when {
        view.visibility != View.VISIBLE -> emptySequence()
        view is TextView -> sequenceOf(view.text.toString())
        view is ViewGroup -> (0 until view.childCount).asSequence().flatMap { visibleText(view.getChildAt(it)) }
        else -> emptySequence()
    }

    private fun eventually(attempts: Int = 20, check: () -> Boolean): Boolean {
        repeat(attempts) {
            if (check()) return true
            SystemClock.sleep(100)
        }
        return false
    }
}
