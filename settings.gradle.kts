pluginManagement {
    repositories {
        // 官方源优先
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        
        // 镜像源作为 fallback
        maven { url = uri("https://www.jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 官方源优先
        google()
        mavenCentral()
        
        // 镜像源作为 fallback
        maven { url = uri("https://www.jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/releases") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "seerinfo"
include(":app")
