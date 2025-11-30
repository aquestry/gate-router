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

fun main() {
    val configPath = System.getenv("CONFIG_PATH")
        ?: Paths.get("config.yml").toAbsolutePath().toString()

    val gateController = GateController(configPath)
    val dockerClient = DockerClient()

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

                call.respondHtml {
                    head {
                        title("Gate Router - Login")
                        style {
                            unsafe {
                                raw(
                                    """
                                    * { margin: 0; padding: 0; box-sizing: border-box; }
                                    body { 
                                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                                        display: flex; 
                                        justify-content: center; 
                                        align-items: center; 
                                        min-height: 100vh; 
                                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                    }
                                    .login-box { 
                                        background: white;
                                        padding: 50px;
                                        border-radius: 10px;
                                        box-shadow: 0 15px 25px rgba(0,0,0,0.2);
                                        width: 100%;
                                        max-width: 400px;
                                    }
                                    h1 { 
                                        color: #333;
                                        margin-bottom: 30px;
                                        text-align: center;
                                    }
                                    input { 
                                        display: block;
                                        width: 100%;
                                        margin: 15px 0;
                                        padding: 15px;
                                        border: 2px solid #ddd;
                                        border-radius: 5px;
                                        font-size: 16px;
                                        transition: border-color 0.3s;
                                    }
                                    input:focus {
                                        outline: none;
                                        border-color: #667eea;
                                    }
                                    button { 
                                        width: 100%;
                                        padding: 15px;
                                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                        color: white;
                                        border: none;
                                        border-radius: 5px;
                                        font-size: 16px;
                                        font-weight: bold;
                                        cursor: pointer;
                                        transition: transform 0.2s;
                                    }
                                    button:hover { transform: translateY(-2px); }
                                    button:active { transform: translateY(0); }
                                """.trimIndent()
                                )
                            }
                        }
                    }
                    body {
                        div("login-box") {
                            h1 { +"🌐 Gate Router" }
                            form(action = "/login", method = FormMethod.post) {
                                input(type = InputType.text, name = "username") {
                                    placeholder = "Username"
                                    required = true
                                }
                                input(type = InputType.password, name = "password") {
                                    placeholder = "Password"
                                    required = true
                                }
                                button(type = ButtonType.submit) { +"Login" }
                            }
                        }
                    }
                }
            }

            post("/login") {
                val params = call.receiveParameters()
                val username = params["username"]
                val password = params["password"]

                if (username == "admin" && password == "admin") {
                    call.sessions.set(UserSession(username))
                    call.respondRedirect("/dashboard")
                } else {
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

                call.respondHtml {
                    head {
                        title("Gate Router - Dashboard")
                        style {
                            unsafe {
                                raw(
                                    """
                                    * { margin: 0; padding: 0; box-sizing: border-box; }
                                    body { 
                                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                                        background: #f5f5f5;
                                        padding: 20px;
                                    }
                                    .header {
                                        background: white;
                                        padding: 20px;
                                        border-radius: 10px;
                                        margin-bottom: 20px;
                                        box-shadow: 0 2px 5px rgba(0,0,0,0.1);
                                        display: flex;
                                        justify-content: space-between;
                                        align-items: center;
                                    }
                                    h1 { color: #333; }
                                    .logout { 
                                        background: #dc3545;
                                        color: white;
                                        padding: 10px 20px;
                                        border: none;
                                        border-radius: 5px;
                                        text-decoration: none;
                                        cursor: pointer;
                                    }
                                    .content {
                                        background: white;
                                        padding: 30px;
                                        border-radius: 10px;
                                        box-shadow: 0 2px 5px rgba(0,0,0,0.1);
                                    }
                                    table { 
                                        width: 100%;
                                        border-collapse: collapse;
                                        margin-top: 20px;
                                    }
                                    th, td { 
                                        padding: 15px;
                                        text-align: left;
                                        border-bottom: 1px solid #ddd;
                                    }
                                    th { 
                                        background: #667eea;
                                        color: white;
                                    }
                                    tr:hover { background: #f9f9f9; }
                                    .add-btn {
                                        display: inline-block;
                                        background: #28a745;
                                        color: white;
                                        padding: 12px 25px;
                                        border-radius: 5px;
                                        text-decoration: none;
                                        margin-bottom: 20px;
                                    }
                                    .delete-btn {
                                        background: #dc3545;
                                        color: white;
                                        padding: 5px 15px;
                                        border: none;
                                        border-radius: 3px;
                                        cursor: pointer;
                                    }
                                """.trimIndent()
                                )
                            }
                        }
                    }
                    body {
                        div("header") {
                            h1 { +"🌐 Gate Router Dashboard" }
                            a(href = "/logout", classes = "logout") { +"Logout" }
                        }
                        div("content") {
                            a(href = "/add-host", classes = "add-btn") { +"+ Add New Host" }

                            h2 { +"Configured Hosts" }

                            if (hosts.isEmpty()) {
                                p { +"No hosts configured yet." }
                            } else {
                                table {
                                    tr {
                                        th { +"Domain" }
                                        th { +"Target Address" }
                                        th { +"Actions" }
                                    }
                                    hosts.forEach { entry ->
                                        val domain = entry.key
                                        val address = entry.value
                                        tr {
                                            td { +domain }
                                            td { +address }
                                            td {
                                                form(action = "/delete-host", method = FormMethod.post) {
                                                    style = "display: inline;"
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

            get("/add-host") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respondRedirect("/")
                    return@get
                }

                val containers = dockerClient.listMinecraftContainers()

                call.respondHtml {
                    head {
                        title("Add Host - Gate Router")
                        style {
                            unsafe {
                                raw(
                                    """
                                    * { margin: 0; padding: 0; box-sizing: border-box; }
                                    body { 
                                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                                        background: #f5f5f5;
                                        padding: 20px;
                                    }
                                    .container {
                                        max-width: 600px;
                                        margin: 50px auto;
                                        background: white;
                                        padding: 40px;
                                        border-radius: 10px;
                                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                                    }
                                    h1 { 
                                        color: #333;
                                        margin-bottom: 30px;
                                    }
                                    label {
                                        display: block;
                                        margin-bottom: 8px;
                                        color: #555;
                                        font-weight: bold;
                                    }
                                    input, select {
                                        width: 100%;
                                        padding: 12px;
                                        margin-bottom: 20px;
                                        border: 2px solid #ddd;
                                        border-radius: 5px;
                                        font-size: 16px;
                                    }
                                    button {
                                        width: 100%;
                                        padding: 15px;
                                        background: #28a745;
                                        color: white;
                                        border: none;
                                        border-radius: 5px;
                                        font-size: 16px;
                                        font-weight: bold;
                                        cursor: pointer;
                                    }
                                    .back {
                                        display: inline-block;
                                        margin-bottom: 20px;
                                        color: #667eea;
                                        text-decoration: none;
                                    }
                                """.trimIndent()
                                )
                            }
                        }
                    }
                    body {
                        div("container") {
                            a(href = "/dashboard", classes = "back") { +"← Back to Dashboard" }
                            h1 { +"Add New Host" }

                            form(action = "/add-host", method = FormMethod.post) {
                                label { +"Domain (e.g., survival.example.com)" }
                                input(type = InputType.text, name = "domain") {
                                    placeholder = "survival.example.com"
                                    required = true
                                }

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

                                label { +"Server Port (default 25565)" }
                                input(type = InputType.number, name = "port") {
                                    value = "25565"
                                    min = "1"
                                    max = "65535"
                                    required = true
                                }

                                button(type = ButtonType.submit) { +"Add Host" }
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

                if (domain.isNotBlank() && containerId.isNotBlank()) {
                    gateController.addHost(domain, "$containerId:$port")
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
