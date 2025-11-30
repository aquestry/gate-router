package dev.aquestry.gaterouter

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.html.*
import io.ktor.server.sessions.*
import io.ktor.server.request.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Paths
import java.nio.file.Files
import com.charleskorn.kaml.Yaml
import com.github.dockerjava.api.DockerClient as JDockerClient
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient

@Serializable
data class GateFile(
    val config: GateLiteWrapper
)

@Serializable
data class GateLiteWrapper(
    val lite: LiteConfig
)

@Serializable
data class LiteConfig(
    val enabled: Boolean = true,
    val routes: List<RouteConfig> = emptyList()
)

@Serializable
data class RouteConfig(
    val host: String,
    val backend: String
)

@Serializable
data class NotesFile(
    val notes: Map<String, String> = emptyMap()
)

data class UserSession(val username: String)

class GateController(private val configPath: String) {
    private var state: GateFile = loadFromDiskOrDefault()

    private fun defaultState(): GateFile =
        GateFile(
            config = GateLiteWrapper(
                lite = LiteConfig(
                    enabled = true,
                    routes = emptyList()
                )
            )
        )

    private fun loadFromDiskOrDefault(): GateFile {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            return defaultState()
        }
        val text = configFile.readText()
        if (text.isBlank()) {
            return defaultState()
        }
        return try {
            Yaml.default.decodeFromString(GateFile.serializer(), text)
        } catch (e: Exception) {
            println("Failed to parse config.yml: ${e.message}")
            defaultState()
        }
    }

    private fun saveToDisk() {
        val yaml = Yaml.default.encodeToString(GateFile.serializer(), state)
        val configFile = File(configPath)
        configFile.writeText(yaml)
    }

    fun getHosts(): Map<String, String> =
        state.config.lite.routes.associate { it.host to it.backend }

    fun addHost(domain: String, containerAddress: String) {
        val currentLite = state.config.lite
        val updatedRoutes = currentLite.routes + RouteConfig(host = domain, backend = containerAddress)
        state = GateFile(
            config = GateLiteWrapper(
                lite = LiteConfig(
                    enabled = currentLite.enabled,
                    routes = updatedRoutes
                )
            )
        )
        saveToDisk()
        restartGate()
    }

    fun removeHost(domain: String) {
        val currentLite = state.config.lite
        val filteredRoutes = currentLite.routes.filterNot { it.host == domain }
        if (filteredRoutes.size == currentLite.routes.size) return
        state = GateFile(
            config = GateLiteWrapper(
                lite = LiteConfig(
                    enabled = currentLite.enabled,
                    routes = filteredRoutes
                )
            )
        )
        saveToDisk()
        restartGate()
    }

    private fun restartGate() {
        println("Gate config updated, restart required")
    }
}

class NotesController(private val notesPath: String) {
    private var state: NotesFile = loadFromDiskOrDefault()

    private fun loadFromDiskOrDefault(): NotesFile {
        val file = File(notesPath)
        if (!file.exists()) return NotesFile()
        val text = file.readText()
        if (text.isBlank()) return NotesFile()
        return try {
            Yaml.default.decodeFromString(NotesFile.serializer(), text)
        } catch (e: Exception) {
            println("Failed to parse notes.yml: ${e.message}")
            NotesFile()
        }
    }

    private fun saveToDisk() {
        val yaml = Yaml.default.encodeToString(NotesFile.serializer(), state)
        val file = File(notesPath)
        file.writeText(yaml)
    }

    fun getNotes(): Map<String, String> = state.notes

    fun setNote(domain: String, note: String) {
        val trimmed = note.trim().take(16)
        val newMap = if (trimmed.isEmpty()) {
            state.notes - domain
        } else {
            state.notes + (domain to trimmed)
        }
        state = NotesFile(newMap)
        saveToDisk()
    }

    fun removeNote(domain: String) {
        if (!state.notes.containsKey(domain)) return
        state = NotesFile(state.notes - domain)
        saveToDisk()
    }
}

class DockerClient(
    private val defaultPort: Int = 25565
) {
    private val host: String = System.getenv("DOCKER_HOST")
        ?: if (System.getProperty("os.name").contains("win", true)) "tcp://localhost:2375"
        else if (Files.exists(Paths.get("/var/run/docker.sock"))) "unix:///var/run/docker.sock"
        else "tcp://host.docker.internal:2375"

    private val config = DefaultDockerClientConfig
        .createDefaultConfigBuilder()
        .withDockerHost(host)
        .build()

    private val httpClient: DockerHttpClient = ApacheDockerHttpClient.Builder()
        .dockerHost(config.dockerHost)
        .build()

    private val client: JDockerClient = DockerClientImpl.getInstance(config, httpClient)

    data class MinecraftContainer(
        val id: String,
        val name: String,
        val hasDefaultPort: Boolean
    )

    fun listMinecraftContainers(): List<MinecraftContainer> =
        try {
            client.listContainersCmd()
                .withShowAll(true)
                .withNetworkFilter(listOf("pterodactyl_nw"))
                .exec()
                .mapNotNull { c: Container ->
                    val id = c.id ?: return@mapNotNull null
                    val name = c.names?.firstOrNull()?.removePrefix("/") ?: id
                    val hasPort = (c.ports ?: emptyArray()).any { p ->
                        p.privatePort == defaultPort
                    }
                    MinecraftContainer(
                        id = id,
                        name = name,
                        hasDefaultPort = hasPort
                    )
                }
        } catch (e: Exception) {
            println("Error listing containers via Docker API: ${e.message}")
            emptyList()
        }
}

class LoginRateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMillis: Long = 60_000,
    private val lockMillis: Long = 300_000
) {
    private data class Entry(var attempts: MutableList<Long>, var lockedUntil: Long)
    private val entries = mutableMapOf<String, Entry>()

    fun isAllowed(key: String, now: Long): Boolean {
        val entry = entries[key]
        if (entry != null) {
            if (entry.lockedUntil > now) return false
            entry.attempts = entry.attempts.filter { now - it <= windowMillis }.toMutableList()
            if (entry.attempts.size >= maxAttempts) {
                entry.lockedUntil = now + lockMillis
                return false
            }
        }
        return true
    }

    fun registerFailure(key: String, now: Long) {
        val entry = entries.getOrPut(key) { Entry(mutableListOf(), 0) }
        entry.attempts.add(now)
        entry.attempts = entry.attempts.filter { now - it <= windowMillis }.toMutableList()
        if (entry.attempts.size >= maxAttempts) {
            entry.lockedUntil = now + lockMillis
        }
    }

    fun reset(key: String) {
        entries.remove(key)
    }
}

fun main() {
    val configPath = System.getenv("CONFIG_PATH")
        ?: Paths.get("config.yml").toAbsolutePath().toString()
    val notesPath = System.getenv("NOTES_PATH")
        ?: Paths.get("notes.yml").toAbsolutePath().toString()

    val gateController = GateController(configPath)
    val notesController = NotesController(notesPath)
    val dockerClient = DockerClient()
    val adminPassword = System.getenv("PASSWORD") ?: "1234"
    val rateLimiter = LoginRateLimiter()

    embeddedServer(Netty, port = 8080) {
        install(Sessions) {
            cookie<UserSession>("user_session") {
                cookie.path = "/"
                cookie.maxAgeInSeconds = 3600
            }
        }

        routing {
            get("/") {
                val session = call.sessions.get<UserSession>()
                if (session != null) {
                    call.respondRedirect("/dashboard")
                    return@get
                }
                val error = call.request.queryParameters["error"]

                call.respondHtml {
                    head {
                        title("Gate Router · Login")
                        style {
                            unsafe {
                                raw(
                                    """
                                    @import url('//fonts.googleapis.com/css?family=Rubik:300,400,500&display=swap');
                                    * { margin: 0; padding: 0; box-sizing: border-box; }
                                    html, body { height: 100%; }
                                    body {
                                        font-family: Rubik, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                                        background-color: #33404d;
                                        display: flex;
                                        align-items: center;
                                        justify-content: center;
                                        padding: 1.5rem;
                                        color: #ffffff;
                                    }
                                    .auth-shell {
                                        width: 100%;
                                        max-width: 440px;
                                    }
                                    .brand {
                                        display: flex;
                                        align-items: center;
                                        gap: .5rem;
                                        margin-bottom: 1.25rem;
                                        color: #ffffff;
                                        font-size: .875rem;
                                        text-transform: uppercase;
                                        letter-spacing: .12em;
                                    }
                                    .brand-icon {
                                        width: 32px;
                                        height: 32px;
                                        border-radius: 999px;
                                        background-color: #3f4d5a;
                                        display: flex;
                                        align-items: center;
                                        justify-content: center;
                                        font-weight: 500;
                                        color: #7b8793;
                                    }
                                    .card {
                                        background: #3f4d5a;
                                        border-radius: .5rem;
                                        border: 1px solid #29333d;
                                        box-shadow: 0 20px 35px rgba(0,0,0,.45);
                                        padding: 2rem;
                                    }
                                    .card-title {
                                        font-size: 1.15rem;
                                        font-weight: 500;
                                        margin-bottom: .25rem;
                                        color: #ffffff;
                                    }
                                    .card-subtitle {
                                        font-size: .875rem;
                                        color: #d1d5db;
                                        margin-bottom: 1.25rem;
                                    }
                                    .field-label {
                                        display: block;
                                        font-size: .75rem;
                                        text-transform: uppercase;
                                        letter-spacing: .12em;
                                        color: #cbd5e1;
                                        margin-bottom: .35rem;
                                    }
                                    .input-row {
                                        display: flex;
                                        flex-direction: column;
                                        gap: .25rem;
                                        margin-bottom: 1rem;
                                    }
                                    .input {
                                        width: 100%;
                                        padding: .7rem .85rem;
                                        border-radius: .375rem;
                                        border: 1px solid #29333d;
                                        background-color: #33404d;
                                        color: #ffffff;
                                        font-size: .875rem;
                                        outline: none;
                                        transition: border-color .15s, box-shadow .15s, background-color .15s;
                                    }
                                    .input::placeholder {
                                        color: #7b8793;
                                    }
                                    .input:focus {
                                        border-color: #2563eb;
                                        box-shadow: 0 0 0 1px rgba(37,99,235,.7);
                                        background-color: #33404d;
                                    }
                                    .btn {
                                        width: 100%;
                                        margin-top: .75rem;
                                        padding: .7rem 1rem;
                                        border-radius: .375rem;
                                        border: none;
                                        font-size: .8rem;
                                        font-weight: 500;
                                        text-transform: uppercase;
                                        letter-spacing: .18em;
                                        background-color: #2563eb;
                                        color: #ffffff;
                                        cursor: pointer;
                                    }
                                    .btn:hover {
                                        background-color: #1d4ed8;
                                    }
                                    .error {
                                        margin-top: .5rem;
                                        font-size: .75rem;
                                        color: #fecaca;
                                    }
                                    .hint {
                                        margin-top: .75rem;
                                        font-size: .75rem;
                                        color: #d1d5db;
                                    }
                                """.trimIndent()
                                )
                            }
                        }
                    }
                    body {
                        div("auth-shell") {
                            div("brand") {
                                div("brand-icon") { +"GR" }
                                span { +"Gate Router" }
                            }
                            div("card") {
                                h1(classes = "card-title") { +"Sign in" }
                                p(classes = "card-subtitle") { +"Authenticate to manage Gate Lite routes." }
                                form(action = "/login", method = FormMethod.post) {
                                    div("input-row") {
                                        label(classes = "field-label") { +"Password" }
                                        input(type = InputType.password, name = "password") {
                                            classes = setOf("input")
                                            placeholder = "••••••••"
                                            required = true
                                            autoComplete = false
                                        }
                                    }
                                    button(type = ButtonType.submit, classes = "btn") { +"Log in" }
                                    when (error) {
                                        "invalid" -> div("error") { +"Invalid password." }
                                        "rate" -> div("error") { +"Too many attempts. Try again later." }
                                    }
                                    div("hint") { +"Default password is 1234 (override via PASSWORD env)." }
                                }
                            }
                        }
                    }
                }
            }

            post("/login") {
                val remote = call.request.headers["X-Real-IP"]
                    ?: call.request.headers["X-Forwarded-For"]
                    ?: call.request.host()
                val now = System.currentTimeMillis()
                if (!rateLimiter.isAllowed(remote, now)) {
                    call.respondRedirect("/?error=rate")
                    return@post
                }
                val params = call.receiveParameters()
                val password = params["password"].orEmpty()
                if (password == adminPassword) {
                    rateLimiter.reset(remote)
                    call.sessions.set(UserSession("admin"))
                    call.respondRedirect("/dashboard")
                } else {
                    rateLimiter.registerFailure(remote, now)
                    call.respondRedirect("/?error=invalid")
                }
            }

            get("/dashboard") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respondRedirect("/")
                    return@get
                }

                val hosts = gateController.getHosts()
                val notes = notesController.getNotes()

                call.respondHtml {
                    head {
                        title("Gate Router · Dashboard")
                        style {
                            unsafe {
                                raw(
                                    """
                                    @import url('//fonts.googleapis.com/css?family=Rubik:300,400,500&display=swap');
                                    * { margin: 0; padding: 0; box-sizing: border-box; }
                                    body {
                                        font-family: Rubik, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                                        background-color: #33404d;
                                        color: #ffffff;
                                    }
                                    .navbar {
                                        background-color: #1f2933;
                                        color: #ffffff;
                                        padding: .7rem 1.5rem;
                                        display: flex;
                                        align-items: center;
                                        justify-content: space-between;
                                    }
                                    .navbar-title {
                                        font-size: 1rem;
                                        font-weight: 500;
                                    }
                                    .navbar-right a {
                                        font-size: .8rem;
                                        text-transform: uppercase;
                                        letter-spacing: .14em;
                                        color: #ffffff;
                                        text-decoration: none;
                                        padding: .35rem .85rem;
                                        border-radius: .375rem;
                                        border: 1px solid rgba(255,255,255,.75);
                                    }
                                    .page-shell {
                                        padding: 1.5rem;
                                    }
                                    .content {
                                        max-width: 960px;
                                        margin: 0 auto;
                                    }
                                    .card {
                                        background-color: #3f4d5a;
                                        border-radius: .5rem;
                                        border: 1px solid #29333d;
                                        padding: 1.5rem 1.75rem;
                                        box-shadow: 0 18px 30px rgba(0,0,0,.4);
                                    }
                                    .card-header {
                                        display: flex;
                                        justify-content: space-between;
                                        align-items: center;
                                        margin-bottom: 1rem;
                                    }
                                    .card-title {
                                        font-size: 1rem;
                                        font-weight: 500;
                                    }
                                    .card-subtitle {
                                        font-size: .85rem;
                                        color: #d1d5db;
                                        margin-top: .25rem;
                                    }
                                    .add-btn {
                                        display: inline-flex;
                                        align-items: center;
                                        justify-content: center;
                                        padding: .45rem 1rem;
                                        border-radius: .375rem;
                                        background-color: #2563eb;
                                        color: #ffffff;
                                        font-size: .75rem;
                                        font-weight: 500;
                                        letter-spacing: .12em;
                                        text-transform: uppercase;
                                        text-decoration: none;
                                    }
                                    table {
                                        width: 100%;
                                        border-collapse: collapse;
                                        margin-top: .5rem;
                                    }
                                    th, td {
                                        padding: .55rem .5rem;
                                        font-size: .875rem;
                                        vertical-align: middle;
                                    }
                                    th {
                                        font-size: .75rem;
                                        text-transform: uppercase;
                                        letter-spacing: .12em;
                                        color: #d1d5db;
                                        border-bottom: 1px solid #29333d;
                                    }
                                    tr:nth-child(even) td {
                                        background-color: #3f4d5a;
                                    }
                                    tr:nth-child(odd) td {
                                        background-color: #3f4d5a;
                                    }
                                    tr:hover td {
                                        background-color: #455667;
                                    }
                                    .delete-btn {
                                        border: none;
                                        border-radius: .375rem;
                                        padding: .3rem .7rem;
                                        font-size: .7rem;
                                        text-transform: uppercase;
                                        letter-spacing: .12em;
                                        background-color: #991b1b;
                                        color: #fee2e2;
                                        cursor: pointer;
                                    }
                                    .note-input {
                                        width: 100%;
                                        padding: .35rem .4rem;
                                        border-radius: .375rem;
                                        border: 1px solid #29333d;
                                        background-color: #33404d;
                                        color: #ffffff;
                                        font-size: .75rem;
                                        outline: none;
                                    }
                                    .note-input:focus {
                                        border-color: #2563eb;
                                    }
                                """.trimIndent()
                                )
                            }
                        }
                    }
                    body {
                        div("navbar") {
                            div("navbar-title") { +"Gate Router" }
                            div("navbar-right") {
                                a(href = "/logout") { +"Logout" }
                            }
                        }
                        div("page-shell") {
                            div("content") {
                                div("card") {
                                    div("card-header") {
                                        div {
                                            div("card-title") { +"Configured Hosts" }
                                            div("card-subtitle") { +"Manage Gate Lite routes and host notes." }
                                        }
                                        a(href = "/add-host", classes = "add-btn") { +"+ Add New Host" }
                                    }
                                    if (hosts.isEmpty()) {
                                        p {
                                            style = "font-size: .875rem; color: #d1d5db; margin-top: .5rem;"
                                            +"No hosts configured yet."
                                        }
                                    } else {
                                        table {
                                            tr {
                                                th { +"Domain" }
                                                th { +"Backend" }
                                                th { +"Note (max 16 chars)" }
                                                th { +"Actions" }
                                            }
                                            hosts.forEach { entry ->
                                                val domain = entry.key
                                                val address = entry.value
                                                val note = notes[domain] ?: ""
                                                tr {
                                                    td { +domain }
                                                    td { +address }
                                                    td {
                                                        form(action = "/update-note", method = FormMethod.post) {
                                                            hiddenInput(name = "domain") { value = domain }
                                                            input(type = InputType.text, name = "note") {
                                                                classes = setOf("note-input")
                                                                value = note
                                                                attributes["maxlength"] = "16"
                                                                attributes["onchange"] = "this.form.submit()"
                                                            }
                                                        }
                                                    }
                                                    td {
                                                        form(action = "/delete-host", method = FormMethod.post) {
                                                            style = "display:inline;"
                                                            hiddenInput(name = "domain") { value = domain }
                                                            button(type = ButtonType.submit, classes = "delete-btn") { +"Delete" }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            get("/add-host") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respondRedirect("/")
                    return@get
                }

                val containers = dockerClient.listMinecraftContainers()

                call.respondHtml {
                    head {
                        title("Gate Router · Add Host")
                        style {
                            unsafe {
                                raw(
                                    """
                                    @import url('//fonts.googleapis.com/css?family=Rubik:300,400,500&display=swap');
                                    * { margin: 0; padding: 0; box-sizing: border-box; }
                                    body {
                                        font-family: Rubik, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                                        background-color: #33404d;
                                        color: #ffffff;
                                    }
                                    .navbar {
                                        background-color: #1f2933;
                                        color: #ffffff;
                                        padding: .7rem 1.5rem;
                                        display: flex;
                                        align-items: center;
                                        justify-content: space-between;
                                    }
                                    .navbar-title {
                                        font-size: 1rem;
                                        font-weight: 500;
                                    }
                                    .navbar-right a {
                                        font-size: .8rem;
                                        text-transform: uppercase;
                                        letter-spacing: .14em;
                                        color: #ffffff;
                                        text-decoration: none;
                                        padding: .35rem .85rem;
                                        border-radius: .375rem;
                                        border: 1px solid rgba(255,255,255,.75);
                                    }
                                    .page-shell {
                                        padding: 1.5rem;
                                    }
                                    .container {
                                        max-width: 640px;
                                        margin: 2rem auto;
                                        background-color: #3f4d5a;
                                        border-radius: .5rem;
                                        border: 1px solid #29333d;
                                        box-shadow: 0 18px 30px rgba(0,0,0,.4);
                                        padding: 1.75rem 1.9rem;
                                    }
                                    .back {
                                        display: inline-flex;
                                        align-items: center;
                                        font-size: .75rem;
                                        text-transform: uppercase;
                                        letter-spacing: .12em;
                                        color: #d1d5db;
                                        text-decoration: none;
                                        margin-bottom: 1rem;
                                    }
                                    h1 {
                                        font-size: 1.1rem;
                                        font-weight: 500;
                                        margin-bottom: 1.25rem;
                                        color: #ffffff;
                                    }
                                    .form-group {
                                        display: flex;
                                        flex-direction: column;
                                        margin-bottom: 1rem;
                                    }
                                    label {
                                        font-size: .75rem;
                                        text-transform: uppercase;
                                        letter-spacing: .12em;
                                        color: #cbd5e1;
                                        margin-bottom: .35rem;
                                    }
                                    input, select {
                                        width: 100%;
                                        padding: .7rem .85rem;
                                        border-radius: .375rem;
                                        border: 1px solid #29333d;
                                        background-color: #33404d;
                                        color: #ffffff;
                                        font-size: .875rem;
                                        outline: none;
                                    }
                                    input:focus, select:focus {
                                        border-color: #2563eb;
                                    }
                                    button {
                                        width: 100%;
                                        padding: .7rem 1rem;
                                        border-radius: .375rem;
                                        border: none;
                                        font-size: .75rem;
                                        font-weight: 500;
                                        text-transform: uppercase;
                                        letter-spacing: .18em;
                                        background-color: #2563eb;
                                        color: #ffffff;
                                        cursor: pointer;
                                    }
                                """.trimIndent()
                                )
                            }
                        }
                    }
                    body {
                        div("navbar") {
                            div("navbar-title") { +"Gate Router" }
                            div("navbar-right") {
                                a(href = "/logout") { +"Logout" }
                            }
                        }
                        div("page-shell") {
                            div("container") {
                                a(href = "/dashboard", classes = "back") { +"← Back to Dashboard" }
                                h1 { +"Add New Host" }
                                form(action = "/add-host", method = FormMethod.post) {
                                    div("form-group") {
                                        label { +"Domain (e.g., survival.example.com)" }
                                        input(type = InputType.text, name = "domain") {
                                            placeholder = "survival.example.com"
                                            required = true
                                        }
                                    }
                                    div("form-group") {
                                        label { +"Select Minecraft Server Container" }
                                        select {
                                            name = "container"
                                            required = true
                                            containers.forEach { c ->
                                                option {
                                                    value = c.id
                                                    val marker = if (c.hasDefaultPort) " (25565)" else ""
                                                    +("${c.name}$marker (${c.id.take(12)})")
                                                }
                                            }
                                            if (containers.isEmpty()) {
                                                option {
                                                    disabled = true
                                                    +"No containers found"
                                                }
                                            }
                                        }
                                    }
                                    div("form-group") {
                                        label { +"Server Port (default 25565)" }
                                        input(type = InputType.number, name = "port") {
                                            value = "25565"
                                            min = "1"
                                            max = "65535"
                                            required = true
                                        }
                                    }
                                    div("form-group") {
                                        label { +"Note (optional, max 16 chars)" }
                                        input(type = InputType.text, name = "note") {
                                            attributes["maxlength"] = "16"
                                            placeholder = "Short note"
                                        }
                                    }
                                    button(type = ButtonType.submit) { +"Add Host" }
                                }
                            }
                        }
                    }
                }
            }

            post("/add-host") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respondRedirect("/")
                    return@post
                }
                val params = call.receiveParameters()
                val domain = params["domain"].orEmpty()
                val containerId = params["container"].orEmpty()
                val port = params["port"]?.toIntOrNull() ?: 25565
                val note = params["note"].orEmpty()
                if (domain.isNotBlank() && containerId.isNotBlank()) {
                    gateController.addHost(domain, "$containerId:$port")
                    if (note.isNotBlank()) {
                        notesController.setNote(domain, note)
                    }
                }
                call.respondRedirect("/dashboard")
            }

            post("/update-note") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respondRedirect("/")
                    return@post
                }
                val params = call.receiveParameters()
                val domain = params["domain"].orEmpty()
                val note = params["note"].orEmpty()
                if (domain.isNotBlank()) {
                    notesController.setNote(domain, note)
                }
                call.respondRedirect("/dashboard")
            }

            post("/delete-host") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respondRedirect("/")
                    return@post
                }
                val params = call.receiveParameters()
                val domain = params["domain"].orEmpty()
                if (domain.isNotBlank()) {
                    gateController.removeHost(domain)
                    notesController.removeNote(domain)
                }
                call.respondRedirect("/dashboard")
            }

            get("/logout") {
                call.sessions.clear<UserSession>()
                call.respondRedirect("/")
            }
        }
    }.start(wait = true)
}
