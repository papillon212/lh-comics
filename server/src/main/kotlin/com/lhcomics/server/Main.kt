package com.lhcomics.server

import com.lhcomics.core.library.ComicLibrary
import com.lhcomics.core.library.Images
import com.lhcomics.core.library.Item
import com.lhcomics.core.library.ItemKind
import com.lhcomics.core.library.Location
import com.lhcomics.core.library.Page
import com.lhcomics.core.source.SourceFactory
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException

@Serializable
data class SourceDto(val id: String, val name: String)

@Serializable
data class ListResponse(val name: String, val location: String, val parent: String?, val items: List<ItemDto>)

@Serializable
data class ItemDto(val name: String, val location: String, val kind: ItemKind, val size: Long, val progress: ProgressDto? = null)

@Serializable
data class BookResponse(
    val title: String,
    val location: String,
    val parent: String?,
    val pages: List<Page>,
    val progress: ProgressDto?,
    val next: Item?,
    val previous: Item?,
    /** 안에 다른 압축 파일이 있어서 폴더로 여는 게 맞는 경우. */
    val browse: Boolean,
)

@Serializable
data class LoginRequest(val password: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class ServerInfo(val authRequired: Boolean, val authenticated: Boolean, val version: String = "0.1.0")

private val log = LoggerFactory.getLogger("lh-comics")

fun main() {
    val config = ServerConfig.fromEnv()
    log.info("sources: {}", config.sources.joinToString { "${it.name}(${it.type}:${it.host})" })
    if (config.password.isEmpty()) log.warn("LHC_PASSWORD 가 비어 있어 인증 없이 열립니다. 외부에 노출하지 마세요.")
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { lhComics(config) }.start(wait = true)
}

class Libraries(config: ServerConfig) : AutoCloseable {
    private val cacheDir = File(config.dataDir, "cache")
    private val factory = SourceFactory(cacheDir)
    val sources = config.sources
    private val libraries = sources.associate { it.id to lazy { ComicLibrary(factory.create(it), cacheDir) } }

    operator fun get(id: String): ComicLibrary =
        libraries[id]?.value ?: throw FileNotFoundException("unknown source: $id")

    override fun close() = libraries.values.filter { it.isInitialized() }.forEach { runCatching { it.value.close() } }
}

fun Application.lhComics(config: ServerConfig) {
    val libraries = Libraries(config)
    val progress = ProgressStore(File(config.dataDir, "progress.json"))
    val auth = AuthStore(config.password, File(config.dataDir, "tokens.json"))
    val covers = CoverCache(File(config.dataDir, "covers"))

    install(ContentNegotiation) { json() }
    install(Compression) {
        gzip { excludeContentType(ContentType.Image.Any) }
    }
    install(CachingHeaders)
    install(StatusPages) {
        exception<FileNotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "not found")) }
        exception<NoSuchElementException> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "not found")) }
        exception<SecurityException> { call, _ -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request")) }
        exception<Throwable> { call, e ->
            log.error("request failed: ${call.request.local.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: e.javaClass.simpleName))
        }
    }
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { libraries.close() }

    routing {
        route("/api") {
            get("/info") {
                call.respond(ServerInfo(auth.enabled, auth.verify(call.token())))
            }
            post("/login") {
                val token = auth.login(call.receive<LoginRequest>().password)
                if (token == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("비밀번호가 틀렸습니다"))
                    return@post
                }
                val secure = call.request.origin.scheme == "https" || call.request.headers["X-Forwarded-Proto"] == "https"
                call.response.cookies.append(
                    Cookie(COOKIE, token, maxAge = 60 * 60 * 24 * 365, path = "/", httpOnly = true, secure = secure, extensions = mapOf("SameSite" to "Lax")),
                )
                call.respond(mapOf("token" to token))
            }
            post("/logout") {
                call.token()?.let(auth::logout)
                call.response.cookies.append(Cookie(COOKIE, "", maxAge = 0, path = "/"))
                call.respond(HttpStatusCode.NoContent)
            }

            // 아래는 로그인이 필요하다.
            get("/sources") { authed(auth) { call.respond(libraries.sources.map { SourceDto(it.id, it.name) }) } }

            get("/list") {
                authed(auth) {
                    val (sourceId, loc) = call.target()
                    val response = io {
                        val items = libraries[sourceId].list(loc)
                        val prog = progress.under(sourceId, items.filter { it.kind != ItemKind.IMAGE }.map { it.location })
                        ListResponse(
                            name = loc.name.ifEmpty { libraries.sources.first { it.id == sourceId }.name },
                            location = loc.encode(),
                            parent = loc.parent()?.encode(),
                            items = items.map { ItemDto(it.name, it.location, it.kind, it.size, prog[it.location]) },
                        )
                    }
                    call.respond(response)
                }
            }

            get("/book") {
                authed(auth) {
                    val (sourceId, loc) = call.target()
                    val response = io {
                        val library = libraries[sourceId]
                        val browse = loc.inArchive && library.containsArchives(loc)
                        BookResponse(
                            title = loc.name,
                            location = loc.encode(),
                            parent = loc.parent()?.encode(),
                            pages = if (browse) emptyList() else library.pages(loc),
                            progress = progress.get(sourceId, loc.encode()),
                            next = runCatching { library.adjacent(loc, 1) }.getOrNull(),
                            previous = runCatching { library.adjacent(loc, -1) }.getOrNull(),
                            browse = browse,
                        )
                    }
                    call.respond(response)
                }
            }

            get("/image") {
                authed(auth) {
                    val (sourceId, loc) = call.target()
                    val width = call.request.queryParameters["w"]?.toIntOrNull()?.coerceIn(200, 4096)
                    val bytes = io { libraries[sourceId].read(loc) }
                    val resized = width?.let { io { Thumbnails.resize(bytes, it) } }
                    call.cachePrivately()
                    if (resized != null) call.respondBytes(resized, ContentType.Image.JPEG)
                    else call.respondBytes(bytes, ContentType.parse(Images.mimeType(loc.dir)))
                }
            }

            get("/cover") {
                authed(auth) {
                    val (sourceId, loc) = call.target()
                    val bytes = io {
                        covers.get(sourceId, loc.encode()) {
                            val library = libraries[sourceId]
                            val page = if (Images.isImage(loc.dir) && !loc.encode().endsWith(Location.SEP)) loc
                            else library.cover(loc)?.let { Location.parse(it.location) }
                            page?.let { library.read(it) }?.let { original -> Thumbnails.resize(original, 360) ?: original }
                        }
                    }
                    if (bytes == null) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.cachePrivately()
                        call.respondBytes(bytes, ContentType.Image.JPEG)
                    }
                }
            }

            get("/progress/recent") { authed(auth) { call.respond(progress.recent()) } }
            put("/progress") {
                authed(auth) {
                    val body = call.receive<ProgressDto>()
                    require(libraries.sources.any { it.id == body.sourceId }) { "unknown source" }
                    progress.save(body)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            delete("/progress") {
                authed(auth) {
                    val (sourceId, loc) = call.target()
                    progress.delete(sourceId, loc.encode())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        // PWA 정적 파일. 라우팅은 해시(#/...) 방식이라 index.html 폴백이 필요 없다.
        if (config.webDir.exists()) {
            staticFiles("/", config.webDir) {
                default("index.html")
                cacheControl { file ->
                    when {
                        file.name == "sw.js" || file.name == "index.html" -> listOf(CacheControl.NoCache(null))
                        file.path.contains("/assets/") -> listOf(CacheControl.MaxAge(60 * 60 * 24 * 365))
                        else -> listOf(CacheControl.MaxAge(60 * 60))
                    }
                }
            }
        } else {
            get("/") { call.respond(HttpStatusCode.OK, "LH Comics server is running. (PWA not found at ${config.webDir})") }
        }
    }
}

private const val COOKIE = "lhc_token"

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** 브라우저는 쿠키, 다른 클라이언트는 Authorization: Bearer 로 보낸다. */
private fun ApplicationCall.token(): String? =
    request.cookies[COOKIE] ?: request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()

private suspend fun RoutingContext.authed(auth: AuthStore, block: suspend RoutingContext.() -> Unit) {
    if (!auth.verify(call.token())) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("login required"))
        return
    }
    block()
}

private fun ApplicationCall.target(): Pair<String, Location> {
    val sourceId = requireNotNull(request.queryParameters["s"]) { "missing s" }
    val loc = Location.parse(request.queryParameters["loc"] ?: "/")
    return sourceId to loc
}

/** 같은 위치의 이미지는 바뀌지 않으므로 브라우저가 오래 캐시하게 한다(로그인 사용자 전용). */
private fun ApplicationCall.cachePrivately() {
    response.header(HttpHeaders.CacheControl, "private, max-age=604800")
}
