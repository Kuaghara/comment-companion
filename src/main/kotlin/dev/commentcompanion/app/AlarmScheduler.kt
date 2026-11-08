package dev.commentcompanion.app

import com.intellij.openapi.Disposable
import com.intellij.util.Alarm
import dev.commentcompanion.ports.SchedulerPort
import java.util.concurrent.ConcurrentHashMap

/** SchedulerPort 的 IntelliJ Alarm 实现（后台线程，不占 EDT）。 */
class AlarmScheduler(parent: Disposable) : SchedulerPort {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
    private val tasks = ConcurrentHashMap<String, Runnable>()

    override fun schedule(key: String, delayMillis: Long, action: () -> Unit) {
        tasks.remove(key)?.let { alarm.cancelRequest(it) }
        val runnable = Runnable {
            tasks.remove(key)
            runCatching { action() }
        }
        tasks[key] = runnable
        alarm.addRequest(runnable, delayMillis)
    }

    override fun cancel(key: String) {
        tasks.remove(key)?.let { alarm.cancelRequest(it) }
    }

    override fun cancelAll() {
        tasks.values.forEach { alarm.cancelRequest(it) }
        tasks.clear()
    }
}
