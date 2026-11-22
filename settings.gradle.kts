pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Midex"
include(":helper")
include(":refresh-unlock")
project(":refresh-unlock").projectDir = file("tools/xring-mode-unlock/lsposed")
