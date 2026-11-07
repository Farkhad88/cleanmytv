package dev.cleanmytv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

fun JSONObject.stringKeys(): List<String> = keys().asSequence().toList()
fun canonical(v: Any?): String = when(v) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> v.stringKeys().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
    is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
    is String -> JSONObject.quote(v)
    is Number -> v.toDouble().let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
    else -> v.toString()
}

/** Verification is an independent read, not a successful-looking mutation response. */
data class Check(val command: String, val expected: String, val kind: String = "exact") {
    fun passes(tv: SelectedTv): Boolean {
        val result = tv.read(command).trim()
        return when(kind) {
            "exact" -> if(command == HOME_QUERY) componentKey(result.lines().last()) == componentKey(expected) else result == expected
            "contains" -> result.contains(expected)
            "package" -> {
                val pair = expected.split('='); val parts = result.split("__DISABLED__")
                parts.size == 2 && pair[0] in SelectedTv.parsePackages(parts[0]) &&
                    (pair[0] in SelectedTv.parsePackages(parts[1])) == (pair[1] == "disabled")
            }
            "json" -> {
                val got = config(result); val want = JSONObject(expected)
                want.stringKeys().all { got.has(it) && canonical(got.get(it)) == canonical(want.get(it)) }
            }
            "sha256" -> result.split(Regex("\\s+")).firstOrNull() == expected
            "wallpaper" -> (Media.readWallpaper(tv)?.let(::sha256) ?: "absent") == expected
            "nonempty" -> result.isNotEmpty()
            else -> error("Unknown verification method.")
        }
    }
    fun json() = JSONObject().put("command", command).put("expected", expected).put("kind", kind)
    companion object { fun from(o: JSONObject) = Check(o.getString("command"), o.getString("expected"), o.getString("kind")) }
}

data class Step(val label: String, val commands: List<String>, val before: List<Check>, val after: List<Check>,
    val undo: List<String>, val restored: List<Check>, val timeoutMs: Int = 30_000) {
    fun json() = JSONObject().put("label", label).put("commands", JSONArray(commands)).put("before", JSONArray(before.map { it.json() }))
        .put("after", JSONArray(after.map { it.json() })).put("undo", JSONArray(undo)).put("restored", JSONArray(restored.map { it.json() })).put("timeout", timeoutMs)
    companion object {
        fun from(o: JSONObject) = Step(o.getString("label"), o.getJSONArray("commands").strings(), o.getJSONArray("before").checks(),
            o.getJSONArray("after").checks(), o.getJSONArray("undo").strings(), o.getJSONArray("restored").checks(), o.getInt("timeout"))
    }
}
fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun JSONArray.checks() = (0 until length()).map { Check.from(getJSONObject(it)) }

data class Plan(val id: String, val title: String, val identity: Identity, val endpoint: String, val created: Long,
    val steps: List<Step>, val warning: String = "", val reversible: Boolean = true) {
    fun json() = JSONObject().put("id", id).put("title", title).put("identity", identity.json()).put("endpoint", endpoint)
        .put("created", created).put("steps", JSONArray(steps.map { it.json() })).put("warning", warning).put("reversible", reversible)
    val digest: String get() = sha256(canonical(json()).toByteArray())
    companion object {
        fun make(tv: SelectedTv, title: String, steps: List<Step>, warning: String = "", reversible: Boolean = true) =
            Plan(UUID.randomUUID().toString(), title, tv.identity, tv.endpoint, System.currentTimeMillis(), steps, warning, reversible)
        fun from(o: JSONObject) = Plan(o.getString("id"), o.getString("title"), Identity.from(o.getJSONObject("identity")), o.getString("endpoint"),
            o.getLong("created"), o.getJSONArray("steps").let { a -> (0 until a.length()).map { Step.from(a.getJSONObject(it)) } }, o.getString("warning"), o.getBoolean("reversible"))
    }
}

data class Journal(val plan: Plan, val phase: String, val attempted: Int = -1, val message: String = "") {
    fun json() = JSONObject().put("version", 1).put("plan", plan.json()).put("phase", phase).put("attempted", attempted).put("message", message)
}

/** Private, durable records survive process death. A damaged record is never executed. */
class RecoveryStore(val directory: File, private val seal: (ByteArray) -> ByteArray = { it }, private val open: (ByteArray) -> ByteArray = { it }) {
    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create recovery storage." } }
    @Synchronized fun save(j: Journal) {
        require(Regex("[a-zA-Z0-9-]+").matches(j.plan.id))
        val body = canonical(j.json())
        val bytes = JSONObject().put("sha256", sha256(body.toByteArray())).put("body", body).toString().toByteArray()
        val target = File(directory, j.plan.id + ".json")
        val temp = File(directory, j.plan.id + ".tmp")
        FileOutputStream(temp).use { it.write(seal(bytes)); it.fd.sync() }
        try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        check(canonical(read(target).json()) == canonical(j.json())) { "Recovery snapshot failed read-back verification." }
    }
    fun read(file: File): Journal {
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        val envelope = JSONObject(open(file.readBytes()).toString(Charsets.UTF_8))
        val body = envelope.getString("body")
        require(sha256(body.toByteArray()) == envelope.getString("sha256")) { "Recovery record checksum failed." }
        val o = JSONObject(body); require(o.getInt("version") == 1)
        return Journal(Plan.from(o.getJSONObject("plan")), o.getString("phase"), o.getInt("attempted"), o.getString("message"))
    }
    fun all(): List<Journal> = directory.listFiles().orEmpty().filter { it.extension == "json" }.map { read(it) }.sortedByDescending { it.plan.created }
    fun get(id: String): Journal { require(Regex("[a-zA-Z0-9-]+").matches(id)); return read(File(directory, "$id.json")) }
}

data class OperationResult(val success: Boolean, val message: String, val rollbackVerified: Boolean = false)

/** Plans are prepared without writes. Approval is one-use and tied to the complete saved plan. */
class OperationEngine(private val tv: SelectedTv, val store: RecoveryStore, private val progress: (String) -> Unit = {}) {
    @Synchronized fun prepare(plan: Plan): Plan {
        tv.checkIdentity(true)
        require(plan.identity == tv.identity && plan.endpoint == tv.endpoint)
        require(plan.steps.isNotEmpty()) { "No changes are needed." }
        require(plan.steps.all { it.after.isNotEmpty() }) { "Every operation needs independent verification." }
        if (plan.reversible) require(plan.steps.all { it.undo.isNotEmpty() && it.restored.isNotEmpty() }) { "No verified recovery route exists." }
        // Later steps may depend on earlier approved ones. Builders snapshot each independent resource.
        store.save(Journal(plan, "prepared"))
        return plan
    }

    @Synchronized fun execute(id: String, approvedDigest: String): OperationResult {
        val saved = store.get(id)
        val plan = saved.plan
        require(saved.phase == "prepared" && plan.digest == approvedDigest) { "This approval is stale or has already been used." }
        require(System.currentTimeMillis() - plan.created < 15 * 60_000) { "The preview expired. Prepare a fresh snapshot." }
        require(plan.identity == tv.identity && plan.endpoint == tv.endpoint) { "This plan belongs to another TV connection." }
        tv.checkIdentity(true)
        var attempted = -1
        try {
            plan.steps.forEachIndexed { i, step ->
                progress("${i + 1}/${plan.steps.size} · ${step.label}")
                tv.checkIdentity(true)
                require(step.before.all { it.passes(tv) }) { "The TV state changed after the preview: ${step.label}." }
                attempted = i
                store.save(Journal(plan, "running", attempted))
                step.commands.forEachIndexed { n, command ->
                    if(step.commands.size > 3) progress("${step.label} · ${n + 1}/${step.commands.size}")
                    checkedWrite(command, step.timeoutMs)
                }
                check(step.after.all { it.passes(tv) }) { "Independent verification failed: ${step.label}." }
            }
            store.save(Journal(plan, "verified", attempted))
            return OperationResult(true, "${plan.title}: verified on ${tv.identity.model}.")
        } catch (e: Exception) {
            val reason = e.message ?: "The connection failed."
            if (attempted < 0) { store.save(Journal(plan, "cancelled", -1, reason)); return OperationResult(false, reason) }
            store.save(Journal(plan, "recovery-needed", attempted, reason))
            if (!plan.reversible) return OperationResult(false, "$reason The result is unconfirmed; this operation cannot be undone.")
            val back = rollback(plan, attempted)
            val message = if (back) "$reason The original state was restored and independently verified." else "$reason Recovery is incomplete. Keep debugging enabled and open Recovery in Finish."
            store.save(Journal(plan, if (back) "rolled-back" else "recovery-needed", attempted, message))
            return OperationResult(false, message, back)
        }
    }

    fun prepareRestore(record: Journal): Plan {
        require(record.plan.identity.stableId == tv.identity.stableId) { "This recovery record belongs to a different TV." }
        require(record.plan.identity.fingerprint == tv.identity.fingerprint) { "The firmware changed. Review individual packages and settings before recovery." }
        require(record.plan.reversible && record.attempted >= 0) { "This record has no reversible changes." }
        require(record.phase in setOf("verified", "running", "recovery-needed")) { "This record does not need restoration." }
        val reverse = record.plan.steps.take(record.attempted + 1).asReversed().map { s ->
            Step("Restore: ${s.label}", s.undo, emptyList(), s.restored, s.commands, s.after, s.timeoutMs)
        }
        // A failed restore must not automatically reapply the unwanted forward operation.
        return prepare(Plan.make(tv, "Restore ${record.plan.title}", reverse, "Restores the recorded original state. Recovery remains available if the connection fails.", false))
    }

    private fun rollback(plan: Plan, attempted: Int): Boolean {
        var verified = true
        for (step in plan.steps.take(attempted + 1).asReversed()) {
            progress("Restoring · ${step.label}")
            try {
                tv.checkIdentity(true)
                step.undo.forEach { checkedWrite(it, step.timeoutMs) }
                if (!step.restored.all { it.passes(tv) }) verified = false
            } catch (_: Exception) { verified = false }
        }
        return verified
    }

    private fun checkedWrite(command: String, timeout: Int) {
        // A distinct marker validates the exit status even on legacy ADB without shell-v2.
        val wrapped = "($command); __cmtv_rc=\$?; printf '\\n__CMTV_EXIT__:%s\\n' \"\$__cmtv_rc\""
        val answer = tv.write(wrapped, timeout)
        require(answer.trimEnd().endsWith("__CMTV_EXIT__:0") && !Regex("(?im)^(Error|Failure|Exception|SecurityException)").containsMatchIn(answer)) {
            "The TV rejected the operation. No success was assumed."
        }
    }
}

private fun componentKey(value: String): String { val p=value.trim().substringBefore('/'); val c=value.trim().substringAfter('/',""); return p+"/"+if(c.startsWith(".")) p+c else c }
