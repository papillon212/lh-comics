package com.lhcomics.server

import com.lhcomics.core.source.SourceConfig
import com.lhcomics.core.source.SourceType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 환경 변수로 설정한다(도커 친화적).
 *
 * - LHC_PASSWORD   비우면 인증 없음(집 안 전용일 때만 권장)
 * - LHC_LIBRARIES  "이름=/경로,이름2=/경로2". 기본값 "만화=/comics"
 * - LHC_DATA       읽은 기록/캐시 폴더. 기본값 /data
 * - LHC_WEB        PWA 정적 파일 폴더. 기본값 /app/web
 * - LHC_PORT       기본값 8080
 *
 * /data/sources.json 이 있으면 SMB/FTP/WebDAV 소스도 추가로 읽는다([SourceConfig] 배열).
 */
data class ServerConfig(
    val password: String,
    val sources: List<SourceConfig>,
    val dataDir: File,
    val webDir: File,
    val port: Int,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val dataDir = File(env["LHC_DATA"] ?: "/data")
            val libraries = (env["LHC_LIBRARIES"] ?: "만화=/comics").split(',').mapNotNull { spec ->
                val name = spec.substringBefore('=', "").trim()
                val path = spec.substringAfter('=', spec).trim()
                if (path.isEmpty()) null else name.ifEmpty { File(path).name } to path
            }
            val locals = libraries.mapIndexed { i, (name, path) ->
                SourceConfig(id = "local$i", name = name, type = SourceType.LOCAL, host = path)
            }
            val extraFile = File(dataDir, "sources.json")
            val extra = if (extraFile.exists()) {
                Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(SourceConfig.serializer()), extraFile.readText())
            } else emptyList()
            return ServerConfig(
                password = env["LHC_PASSWORD"].orEmpty(),
                sources = locals + extra,
                dataDir = dataDir,
                webDir = File(env["LHC_WEB"] ?: "/app/web"),
                port = env["LHC_PORT"]?.toIntOrNull() ?: 8080,
            )
        }
    }
}
