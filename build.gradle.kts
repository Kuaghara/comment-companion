plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 官方推荐二选一：远程 goland(version) 或本地 local(path)。
        // 本机离线优先，用本地安装；换机器时可改成 goland(providers.gradleProperty("platformVersion").get())。
        local(providers.gradleProperty("golandPath").getOrElse("/Users/kaughara/Applications/GoLand.app"))
        bundledPlugin("org.jetbrains.plugins.go")
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
