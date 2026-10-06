plugins {
    java
}

group = "com.dsh"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.130-stable")
    // 内置 7z(LZMA2) 压缩引擎，会一起打进插件 jar
    // 排除传递依赖：内置引擎只使用 7z 写入路径，不需要 commons-io / codec / lang3
    implementation("org.apache.commons:commons-compress:1.28.0") {
        exclude(group = "commons-io", module = "commons-io")
        exclude(group = "commons-codec", module = "commons-codec")
        exclude(group = "org.apache.commons", module = "commons-lang3")
    }
    implementation("org.tukaani:xz:1.10")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

// 把 commons-compress / xz 展开进最终 jar（无需 shadow 插件）
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude(
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            "META-INF/MANIFEST.MF", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "module-info.class", "META-INF/versions/*/module-info.class"
        )
    }
}

tasks.build {
    dependsOn(tasks.jar)
}
