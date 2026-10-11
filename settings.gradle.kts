pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lh-comics"

include(":core", ":server")

// 도커 이미지 빌드처럼 Android SDK가 없는 환경에서는 -PserverOnly로 앱 모듈을 뺀다.
if (!providers.gradleProperty("serverOnly").isPresent) {
    include(":android")
}
