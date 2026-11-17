package com.mbafford.maskedemail

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The part of Fastmail's JMAP API needed to create and list masked email addresses.
 * A port of the `create` and `list` commands of the fastmail-masked-email script. All calls block.
 */
object Fastmail {
    private const val SESSION_URL = "https://api.fastmail.com/jmap/session"
    private const val CAPABILITY = "https://www.fastmail.com/dev/maskedemail"

    class Failure(message: String) : Exception(message)

    private class Session(val token: String, val apiUrl: String, val account: String)

    @Volatile
    private var cached: Session? = null

    /** Checks that the token works and has the Masked Email scope; throws [Failure] if not. */
    fun check(token: String) {
        session(token)
    }

    /** One masked email address as Fastmail records it. */
    data class Masked(
        val id: String,
        val email: String,
        val state: String,
        val description: String,
        val forDomain: String,
        val createdAt: String,
    )

    /** The result of the last successful [list], so the list screen can open without waiting. */
    @Volatile
    var latest: List<Masked>? = null
        private set

    class Created(val id: String, val email: String)

    /** Creates a new address. */
    fun create(token: String, description: String, pending: Boolean): Created {
        val fields = JSONObject()
            .put("state", if (pending) "pending" else "enabled")
            .put("forDomain", "")
            .put("description", description)
        val result = call(token, "MaskedEmail/set", JSONObject().put("create", JSONObject().put("new", fields)))
        val made = result.optJSONObject("created")?.optJSONObject("new")
        if (made == null) {
            val why = result.optJSONObject("notCreated")?.optJSONObject("new")
            throw Failure("Not created: ${why?.let(::explain) ?: "no reason given"}")
        }
        return Created(made.getString("id"), made.getString("email"))
    }

    /**
     * Takes an address out of use. It is removed completely if Fastmail allows that, which it
     * does only for an address that has never received mail; otherwise it is set to "deleted",
     * so mail to it bounces. [id] may be empty if unknown; the address is then looked up.
     */
    fun delete(token: String, id: String, email: String) {
        val target = id.ifEmpty {
            list(token).firstOrNull { it.email == email }?.id ?: return // already gone
        }
        val destroyed = call(token, "MaskedEmail/set", JSONObject().put("destroy", JSONArray().put(target)))
            .optJSONArray("destroyed")
        if (destroyed != null && destroyed.length() > 0) return
        val change = JSONObject().put(target, JSONObject().put("state", "deleted"))
        val result = call(token, "MaskedEmail/set", JSONObject().put("update", change))
        if (result.optJSONObject("updated")?.has(target) != true) {
            val why = result.optJSONObject("notUpdated")?.optJSONObject(target)
            throw Failure("Not deleted: ${why?.let(::explain) ?: "no reason given"}")
        }
    }

    /** Every masked email address on the account, deleted ones included, newest first. */
    fun list(token: String): List<Masked> {
        val found = call(token, "MaskedEmail/get", JSONObject().put("ids", JSONObject.NULL)).getJSONArray("list")
        return List(found.length()) { i ->
            val o = found.getJSONObject(i)
            Masked(
                id = o.getString("id"),
                email = o.getString("email"),
                state = o.optString("state"),
                description = text(o, "description"),
                forDomain = text(o, "forDomain"),
                createdAt = text(o, "createdAt"),
            )
        }.sortedByDescending { it.createdAt }.also { latest = it }
    }

    // optString turns a JSON null into the word "null".
    private fun text(o: JSONObject, name: String): String = if (o.isNull(name)) "" else o.optString(name)

    private fun call(token: String, method: String, arguments: JSONObject): JSONObject {
        val session = session(token)
        arguments.put("accountId", session.account)
        val body = JSONObject()
            .put("using", JSONArray().put("urn:ietf:params:jmap:core").put(CAPABILITY))
            .put("methodCalls", JSONArray().put(JSONArray().put(method).put(arguments).put("0")))
        val response = request(session.apiUrl, token, body).getJSONArray("methodResponses").getJSONArray(0)
        val result = response.getJSONObject(1)
        if (response.getString(0) == "error") {
            throw Failure("Fastmail refused the request: ${explain(result)}")
        }
        return result
    }

    private fun session(token: String): Session {
        cached?.let { if (it.token == token) return it }
        val answer = request(SESSION_URL, token, null)
        val account = answer.optJSONObject("primaryAccounts")?.optString(CAPABILITY).orEmpty()
        if (account.isEmpty()) throw Failure("This API token does not have the Masked Email scope.")
        return Session(token, answer.getString("apiUrl"), account).also { cached = it }
    }

    private fun explain(error: JSONObject): String =
        error.optString("description").ifEmpty { error.optString("type") }.ifEmpty { error.toString() }

    private fun request(url: String, token: String, body: JSONObject?): JSONObject {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = connection.responseCode
                if (code == 401) throw Failure("Fastmail rejected the API token.")
                if (code >= 400) {
                    val detail = connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                    throw Failure("Fastmail answered $code: ${detail.take(300)}")
                }
                return JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            throw Failure("Could not reach Fastmail: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: org.json.JSONException) {
            throw Failure("Fastmail sent an answer this app does not understand: ${e.message}")
        }
    }
}
