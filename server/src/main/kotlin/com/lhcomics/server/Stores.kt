package com.lhcomics.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class ProgressDto(
    val sourceId: String,
    val location: String,
    val title: String,
    val page: Int,
    val pageCount: Int,
    val updatedAt: Long,
)

private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

/** 읽은 기록. 개인용 서버라 JSON 파일 하나면 충분하다. */
class ProgressStore(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), ProgressDto.serializer())
    private val data: MutableMap<String, ProgressDto> =
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(HashMap())
        else HashMap()

    private fun key(sourceId: String, location: String) = "$sourceId|$location"

    @Synchronized
    fun get(sourceId: String, location: String): ProgressDto? = data[key(sourceId, location)]

    @Synchronized
    fun under(sourceId: String, locations: Collection<String>): Map<String, ProgressDto> =
        locations.mapNotNull { loc -> data[key(sourceId, loc)]?.let { loc to it } }.toMap()

    @Synchronized
    fun recent(limit: Int = 30): List<ProgressDto> = data.values.sortedByDescending { it.updatedAt }.take(limit)

    @Synchronized
    fun save(p: ProgressDto) {
        val existing = data[key(p.sourceId, p.location)]
        // 여러 기기에서 읽을 때 늦게 도착한 오래된 기록이 최신 기록을 덮지 않게 한다.
        if (existing != null && existing.updatedAt > p.updatedAt) return
        data[key(p.sourceId, p.location)] = p
        flush()
    }

    @Synchronized
    fun delete(sourceId: String, location: String) {
        if (data.remove(key(sourceId, location)) != null) flush()
    }

    private fun flush() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, data))
        tmp.renameTo(file)
    }
}

/**
 * 비밀번호 한 개로 로그인하고 토큰을 발급한다. 토큰은 해시만 저장해서
 * 데이터 폴더가 유출돼도 바로 쓸 수 없게 한다.
 */
class AuthStore(private val password: String, private val file: File) {
    private val random = SecureRandom()
    private val serializer = MapSerializer(String.serializer(), Long.serializer())
    private val tokens: MutableMap<String, Long> =
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(HashMap())
        else HashMap()

    val enabled: Boolean get() = password.isNotEmpty()

    @Synchronized
    fun login(attempt: String): String? {
        if (!MessageDigest.isEqual(attempt.toByteArray(), password.toByteArray())) return null
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokens[hash(token)] = System.currentTimeMillis()
        flush()
        return token
    }

    @Synchronized
    fun verify(token: String?): Boolean = !enabled || (token != null && tokens.containsKey(hash(token)))

    @Synchronized
    fun logout(token: String) {
        if (tokens.remove(hash(token)) != null) flush()
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun flush() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, tokens))
    }
}
