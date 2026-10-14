plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.ridermonkey"
version = "0.17.3"

// 编译目标 IDE 的安装目录。缺属性时给出可操作的报错，而不是 NoSuchElementException。
// 注意：报错发生在配置阶段，因此 gradlew tasks 之类的任务同样会失败——这是有意的。
val monkeyBrowserIdePath: String = providers.gradleProperty("monkeyBrowser.idePath")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: error(
        "缺少 Gradle 属性 monkeyBrowser.idePath。" +
            "推荐写在用户级配置 ~/.gradle/gradle.properties（本仓库刻意不提交该机器相关路径），" +
            "例如 monkeyBrowser.idePath=D:/Your/IDE；" +
            "也可用 -PmonkeyBrowser.idePath=<路径> 传入。详见 README 的「编译目标 IDE」。"
    )

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 用本机已安装的 IDE 作为编译目标，避免下载 1GB+ 的 IDE 分发包。
        // 换 Rider 目标时可改为：rider("2026.2")
        local(monkeyBrowserIdePath)

        // JCEF 由内置插件提供，local() 不会自动把它加进编译类路径，必须显式声明。
        bundledPlugin("com.intellij.modules.jcef")
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // 262 平台字节码为 Java 25（class file major 69），需要用 JDK 25 编译。
    jvmToolchain(25)
}

intellijPlatform {
    // 构建时不要启动 IDE 生成 searchable options。
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // 不设置 untilBuild：IntelliJ Platform 2024.3+ 的官方建议，避免插件被挡在
            // 未来的 IDE 版本之外。见 verifyPluginProjectConfiguration 的提示。
        }
    }

    // 用本机这台真正的 Rider 2026.2 做二进制兼容性校验（无 GUI）。
    pluginVerification {
        ides {
            local(monkeyBrowserIdePath)
        }

        // 插件名已改为 "Monkey Browser"，不再包含 JetBrains 商标词。
        // 之前用 -mute 跳过 TemplateWordInPluginName 的临时手段已随之移除——
        // 若将来名字里重新出现商标词，verifyPlugin 会直接失败而不是被静默放过。
    }
}

tasks.test {
    useJUnit()
}
/**
 * 校验 GmInfo.HANDLER_VERSION 与插件 version 一致。
 *
 * `GM_info.version` 是给用户脚本看的运行环境版本，写错会误导脚本作者；而读
 * `PluginManagerCore` 属于内部 API，会被 Plugin Verifier 判为 INTERNAL_API_USAGES 直接让构建失败。
 * 因此改为「常量 + 构建期校验」：不一致就构建失败，不靠人记得同步。
 */
tasks.register("checkHandlerVersion") {
    val expected = version.toString()
    val source = layout.projectDirectory.file(
        "src/main/kotlin/com/ridermonkey/userscript/GmInfo.kt"
    )
    doLast {
        val text = source.asFile.readText()
        val needle = "HANDLER_VERSION: String = " + '"' + expected + '"'
        if (!text.contains(needle)) {
            throw GradleException(
                "GmInfo.HANDLER_VERSION 与 build.gradle.kts 的 version(" + expected + ") 不一致"
            )
        }
    }
}

/**
 * 校验注入到页面的 GM 运行时（src/main/resources/js/gm-api.js）的行为。
 *
 * 这段 JS 跑在页面里，**Kotlin 单测覆盖不到**：它改坏了的症状是「脚本在页面上静默失效」，
 * 很难从插件侧发现。典型回归：responseType 不再透传、abort 不发出去、句柄字段改名、
 * 未授权的 API 意外泄漏。断言写在 tools/check-gm-runtime.mjs（Node 的 vm 造最小页面沙箱）。
 *
 * **找不到 node 时会明确失败，而不是静默跳过** —— 静默跳过等于没有校验。
 * 确实没有 node 的机器用 -PskipGmRuntimeCheck=true 显式跳过。
 */
val checkGmRuntime = tasks.register<Exec>("checkGmRuntime") {
    group = "verification"
    description = "在 Node 沙箱里校验 gm-api.js 的运行时行为"
    val script = layout.projectDirectory.file("tools/check-gm-runtime.mjs").asFile
    val runtime = layout.projectDirectory.file("src/main/resources/js/gm-api.js").asFile
    inputs.files(script, runtime)
    // 校验任务不该被 up-to-date 跳过：断言本身很便宜。
    outputs.upToDateWhen { false }
    commandLine("node", script.absolutePath, runtime.absolutePath)
    onlyIf { !providers.gradleProperty("skipGmRuntimeCheck").isPresent }
}

tasks.named("check") {
    dependsOn("checkHandlerVersion")
    dependsOn("checkGmRuntime")
}