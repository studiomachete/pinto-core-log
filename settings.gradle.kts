pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GitHub Packages — pinto-core-aws 받기 위함
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/studiomachete/pinto-core-aws")
            credentials {
                val propsFile = java.io.File(System.getProperty("user.home"), ".gradle/gradle.properties")
                val props = java.util.Properties()
                if (propsFile.exists()) {
                    propsFile.inputStream().use { props.load(it) }
                }
                username = props.getProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME") ?: ""
                password = props.getProperty("gpr.token") ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "pinto-core-log"
include(":library")
