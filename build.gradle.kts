plugins {
    java
    `maven-publish`
    id("io.papermc.paperweight.patcher") version "1.7.1"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

repositories {
    mavenCentral()
    maven(paperMavenPublicUrl) {
        content { onlyForConfigurations(configurations.paperclip.name) }
    }
}

dependencies {
    remapper("net.fabricmc:tiny-remapper:0.10.1:fat")
    decompiler("org.vineflower:vineflower:1.10.1")
    paperclip("io.papermc:paperclip:3.0.3")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
    }

    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }

    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://ci.emc.gs/nexus/content/groups/aikar/")
        maven("https://repo.aikar.co/content/groups/aikar")
        maven("https://repo.md-5.net/content/repositories/releases/")
        maven("https://hub.spigotmc.org/nexus/content/groups/public/")
        maven("https://jitpack.io")
        maven("https://repo.codemc.io/repository/maven-public/")
    }

}

// Oneiric start - 自动小版本号
//
// 需求：每构建一个新 jar，版本号自动 +0.01（版本号最多两位小数）。之前的 Implementation-Version
// 全部派生自根仓库的 git hash（fork 仓库几乎不提交），导致连续多个构建产物
// 显示完全相同的版本号 —— 既分不清哪个 jar 在跑，也无法向用户证明"新 jar 真的部署了"。
//
// 实现：根目录维护 build-version.txt（一个整数，初始 0）。
// 当本次构建的目标里包含 distributable 任务（paperclip / bundler）时，
// 配置阶段把计数器 +1，产物版本号追加上 "+b0.xx"（计数器即"百分之几"）。
// 只编译（compileJava）不打包时不会自增 —— 没有新 jar 就不该有新版本号。
val oneiricVersionFile = rootProject.projectDir.resolve("build-version.txt")
val oneiricDistributableTasks = gradle.startParameter.taskNames
    .map { it.substringAfterLast(':').lowercase() }
    .filter { it.contains("paperclip") || it.contains("bundler") }
val oneiricBuildCounter: Long = if (oneiricDistributableTasks.isNotEmpty()) {
    val previous = if (oneiricVersionFile.exists()) {
        oneiricVersionFile.readText().trim().toLongOrNull() ?: 0L
    } else {
        0L
    }
    val next = previous + 1
    oneiricVersionFile.writeText(next.toString())
    next
} else {
    // 非打包构建：读现值，不递增。
    if (oneiricVersionFile.exists()) oneiricVersionFile.readText().trim().toLongOrNull() ?: 0L else 0L
}
// 1 -> "0.01"，100 -> "1.00"；由 Oneiric-Server 的 jar manifest 在执行期读取。

// Oneiric start - 产物简化命名
//
// paperweight 默认产物名是 "Oneiric-paperclip-1.21.1-R0.1-SNAPSHOT-mojmap.jar"：
// 版本段是 Gradle 的 API 版本号（固定不变），跟构建演进无关，既长又分不清哪次构建。
// 需求是产物名就叫 Oneiric-V0.xx.jar，与 manifest 里的 +b0.xx 同号。
//
// 实现：paperclip 构建完 doLast 里在 build/libs 生成简化名副本。
// 原始长名文件保留 —— paperweight 的任务输出跟踪指向它，删掉会让任务每次都重跑。
tasks.named("createMojmapPaperclipJar") {
    doLast {
        val counter = oneiricBuildCounter
        val version = "${counter / 100}.${(counter % 100).toString().padStart(2, '0')}"
        // Oneiric - 不写 "java.io.File"：Kotlin DSL 里 "java" 会被项目的 java 扩展遮蔽。
        val target = file("build/libs/Oneiric-V$version.jar")
        val source = outputs.files.firstOrNull { it.isFile && it.name.endsWith(".jar") }
        if (source == null) {
            throw IllegalStateException("createMojmapPaperclipJar produced no jar output")
        }
        source.copyTo(target, overwrite = true)
        logger.lifecycle("[Oneiric] 产物: {} (构建号 {})", target.name, version)
    }
}
// Oneiric end - 产物简化命名

// Oneiric end - 自动小版本号

tasks.generateDevelopmentBundle {
    apiCoordinates.set("io.oneiric.core:oneiric-api")
    libraryRepositories.set(
        listOf(
            "https://repo.maven.apache.org/maven2/",
            paperMavenPublicUrl,
        )
    )
}

paperweight {
    serverProject.set(project(":oneiric-server"))

    remapRepo.set("https://maven.fabricmc.net/")
    decompileRepo.set("https://maven.quiltmc.org/")

    useStandardUpstream("luminol") {
        // Oneiric: LuminolMC/Luminol 已归档（组织页仅剩 AboutCanvas，原仓库 404）。
        // 上游同名仓库的归档镜像 LuminolCustomArchive/Luminol 保留了 dev/1.21.1 与全部提交，
        // 因此把 upstream 指向归档镜像，否则 applyPatches 无法 clone 上游。
        url.set(github("LuminolCustomArchive", "Luminol"))
        ref.set(providers.gradleProperty("luminolCommit"))

        withStandardPatcher {
            apiSourceDirPath.set("Luminol-API")
            serverSourceDirPath.set("Luminol-Server")


            apiPatchDir.set(layout.projectDirectory.dir("patches/api"))
            apiOutputDir.set(layout.projectDirectory.dir("Oneiric-API"))

            serverPatchDir.set(layout.projectDirectory.dir("patches/server"))
            serverOutputDir.set(layout.projectDirectory.dir("Oneiric-Server"))
        }

        patchTasks.register("generatedApi") {
            isBareDirectory = true
            upstreamDirPath = "paper-api-generator/generated"
            patchDir = layout.projectDirectory.dir("patches/generatedApi")
            outputDir = layout.projectDirectory.dir("paper-api-generator/generated")
        }
    }
}
