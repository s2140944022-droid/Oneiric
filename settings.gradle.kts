import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "Oneiric"
for (name in listOf("Oneiric-API", "Oneiric-Server")) {
    val projName = name.lowercase()
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}
