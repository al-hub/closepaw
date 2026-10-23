package ai.closepaw.ui.overlay

/** Bind action IDs to the current task because ActionExecuted itself has no task_id. */
internal class IslandEventGate {
    private var session: String? = null
    private var task: String? = null
    private var started = Long.MAX_VALUE
    private val proposed = linkedMapOf<String, Long>()
    fun start(session: String, task: String, timestamp: Long) {
        this.session = session; this.task = task; started = timestamp; proposed.clear()
    }
    fun proposed(session: String, action: String, timestamp: Long) {
        if (this.session != session || task == null || timestamp < started || proposed.size >= 256) return
        proposed.putIfAbsent(action, timestamp)
    }
    fun completed(session: String, action: String, timestamp: Long): Boolean {
        if (this.session != session || task == null) return false
        val at = proposed[action] ?: return false
        if (timestamp < at || timestamp < started) return false
        proposed.remove(action)
        return true
    }
    fun end(session: String, task: String? = null) {
        if (this.session != session || (task != null && this.task != task)) return
        this.task = null; proposed.clear()
    }
}
