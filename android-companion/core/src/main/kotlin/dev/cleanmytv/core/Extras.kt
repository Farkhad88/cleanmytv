package dev.cleanmytv.core

/** Remote input is explicitly requested per button press and never replays after reconnect. */
object RemoteControl {
    val keys = linkedMapOf("Up" to 19, "Left" to 21, "OK" to 23, "Right" to 22, "Down" to 20, "Back" to 4,
        "Home" to 3, "Menu" to 82, "Volume −" to 25, "Mute" to 164, "Volume +" to 24, "Play / pause" to 85)
    fun send(tv: SelectedTv, code: Int) {
        require(code in keys.values) { "Unsupported remote button." }
        tv.write("input keyevent $code")
        tv.checkIdentity(true)
    }
}

class RestartCheck(private val tv: SelectedTv, private val store: RecoveryStore, private val progress: (String) -> Unit,
    private val sleep: (Long) -> Unit = Thread::sleep) {
    fun prepare(stock: String): Plan {
        require(COMPONENT.matches(stock)) { "A saved, verified recovery launcher is required before the restart check." }
        val states = tv.packages(); val pkg = stock.substringBefore('/')
        require(states.containsKey(pkg)) { "The recovery launcher is no longer installed." }
        val original = tv.home() ?: error("The current Home cannot be identified.")
        val step = Step("Restart, reconnect to this TV, verify Home, and restore $pkg if Home fails",
            listOf("reboot"), listOf(Check(HOME_QUERY, original)), listOf(Check("getprop sys.boot_completed", "1")),
            listOf("pm enable --user 0 $pkg", "cmd package set-home-activity --user 0 ${quote(stock)}", "am start -W -n ${quote(stock)}"),
            listOf(Check(HOME_QUERY, stock), Check("pidof $pkg", "", "nonempty")))
        return OperationEngine(tv, store).prepare(Plan.make(tv, "Restart and check Home", listOf(step),
            "The TV will restart. The app will reconnect only to this endpoint and check its identity. If Home fails to run, the approved recovery launcher is restored. Leave debugging enabled.", false))
    }
    fun execute(plan: Plan, approved: String, reconnect: () -> SelectedTv): Pair<OperationResult, SelectedTv?> {
        val record = store.get(plan.id)
        require(record.phase == "prepared" && record.plan.digest == approved && record.plan.digest == plan.digest)
        require(plan.identity == tv.identity && plan.endpoint == tv.endpoint && System.currentTimeMillis() - plan.created < 15 * 60_000)
        tv.checkIdentity(true)
        require(plan.steps.single().before.all { it.passes(tv) }) { "Home changed after the preview. Prepare a fresh restart check." }
        store.save(Journal(plan, "running", 0))
        try { tv.write("reboot") } catch (_: java.io.IOException) { /* A reboot normally closes its own ADB connection. */ }
        tv.shell.close()
        progress("Waiting for the TV to restart…"); sleep(15_000)
        var connected: SelectedTv? = null
        repeat(48) { attempt ->
            if(connected == null) {
                progress("Reconnecting to the selected TV · ${attempt + 1}/48")
                try {
                    val candidate = reconnect()
                    if(candidate.identity != plan.identity) { candidate.shell.close(); error("The endpoint now belongs to a different device. Recovery was blocked.") }
                    connected = candidate
                } catch(e: IllegalArgumentException) { store.save(Journal(plan, "recovery-needed", 0, e.message.orEmpty())); return OperationResult(false, e.message.orEmpty()) to null }
                catch (_: Exception) { sleep(5000) }
            }
        }
        val current = connected ?: run {
            store.save(Journal(plan, "recovery-needed", 0, "TV did not reconnect."))
            return OperationResult(false, "The TV did not reconnect within four minutes. Wireless debugging may have changed its port. Select the TV in Setup and run the read-only health check. Keep debugging enabled.") to null
        }
        repeat(40) {
            try {
                current.checkIdentity(true)
                val home = current.home()
                if(current.read("getprop sys.boot_completed").trim() == "1" && home != null && !home.contains("setupwraith") && current.read("pidof ${home.substringBefore('/')}").trim().isNotEmpty()) {
                    store.save(Journal(plan, "verified", 0))
                    return OperationResult(true, "The TV restarted. Its identity matches, Android finished booting, and $home is running.") to current
                }
            } catch (_: Exception) { }
            sleep(3000)
        }
        var restored = false
        try {
            plan.steps.single().undo.forEach { current.write(it, 60_000) }
            restored = plan.steps.single().restored.all { it.passes(current) }
        } catch (_: Exception) { }
        store.save(Journal(plan, if(restored) "rolled-back" else "recovery-needed", 0))
        return OperationResult(false, if(restored) "Home did not pass the restart check. The approved stock launcher was restored and verified."
            else "Home did not pass the restart check and recovery could not be verified. Keep debugging enabled and open Recovery.", restored) to current
    }
}
