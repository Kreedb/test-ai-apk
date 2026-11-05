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
        // The AoE ncnn runtime was published to JCenter; this mirror still serves it.
        maven("https://repo.huaweicloud.com/repository/maven/") {
            content { includeGroup("com.didi.aoe") }
        }
    }
}

rootProject.name = "MinimalApkTemplate"
include(":app")
