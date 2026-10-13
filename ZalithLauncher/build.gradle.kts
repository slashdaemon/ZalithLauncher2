import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.tasks.MergeSourceSetFolders
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
    id("kotlin-parcelize")
    id("com.movtery.buildkeys")
}

val zalithPackageName = "com.movtery.zalithlauncher"
val launcherAPPName = project.findProperty("launcher_app_name") as? String ?: error("The \"launcher_app_name\" property is not set in gradle.properties.")
val launcherName = project.findProperty("launcher_name") as? String ?: error("The \"launcher_name\" property is not set in gradle.properties.")
val launcherShortName = project.findProperty("launcher_short_name") as? String ?: error("The \"launcher_short_name\" property is not set in gradle.properties.")
val launcherUrl = project.findProperty("url_home") as? String ?: error("The \"url_home\" property is not set in gradle.properties.")

val launcherVersionCode = (project.findProperty("launcher_version_code") as? String)?.toIntOrNull() ?: error("The \"launcher_version_code\" property is not set as an integer in gradle.properties.")
val launcherVersionName = project.findProperty("launcher_version_name") as? String ?: error("The \"launcher_version_name\" property is not set in gradle.properties.")

val defaultOAuthClientID = project.findProperty("oauth_client_id") as? String
val defaultStorePassword = project.findProperty("default_store_password") as? String ?: error("The \"default_store_password\" property is not set in gradle.properties.")
val defaultKeyPassword = project.findProperty("default_key_password") as? String ?: error("The \"default_key_password\" property is not set in gradle.properties.")
val defaultCurseForgeApiKey = project.findProperty("curseforge_api_key") as? String

val projectArch: String = System.getProperty("arch", "all")

fun getKeyFromLocal(envKey: String, fileName: String? = null, default: String? = null): String {
    val key = System.getenv(envKey)
    return key ?: fileName?.let {
        val file = File(rootDir, fileName)
        if (file.canRead() && file.isFile) file.readText() else null
    } ?: default ?: run {
        logger.warn("BUILD: $envKey not set; related features may throw exceptions.")
        ""
    }
}

android {
    namespace = zalithPackageName
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    signingConfigs {
        create("releaseBuild") {
            storeFile = file("tbs_launcher.jks")
            storePassword = getKeyFromLocal("STORE_PASSWORD", ".store_password.txt")
            keyAlias = "tbs"
            keyPassword = getKeyFromLocal("KEY_PASSWORD", ".key_password.txt")
        }
        create("debugBuild") {
            storeFile = file("zalith_launcher_debug.jks")
            storePassword = defaultStorePassword
            keyAlias = "movtery_zalith_debug"
            keyPassword = defaultKeyPassword
        }
    }

    defaultConfig {
        // TBS fork: distinct applicationId so it installs alongside stock ZalithLauncher.
        // namespace (Kotlin/R package) intentionally stays com.movtery.zalithlauncher.
        applicationId = "as.papp.tbs.launcher"
        applicationIdSuffix = ".v2"
        minSdk = 26
        targetSdk = 34
        versionCode = launcherVersionCode
        versionName = launcherVersionName
        manifestPlaceholders["launcher_name"] = launcherAPPName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("releaseBuild")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debugBuild")
        }
    }

    splits {
        val arch = projectArch.takeIf { it != "all" } ?: return@splits
        abi {
            isEnable = true
            reset()
            when (arch) {
                "arm" -> include("armeabi-v7a")
                "arm64" -> include("arm64-v8a")
                "x86" -> include("x86")
                "x86_64" -> include("x86_64")
            }
        }
    }

    ndkVersion = "25.2.9519653"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    compileOptions {
        // sora-editor language-textmate
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            //让 android.util.Log 等框架方法在本地单测中返回默认值而非抛出异常
            isReturnDefaultValues = true
        }
    }
}

// TBS: the StreamCraft jars bundled in the APK. The version comes from assets/tbs/profile.json; the
// jars come from StreamCraft's build/release (override with -PstreamcraftDir=...). Both Android
// variants are staged; the arch cleanup below keeps only the target ABI's jar in an arch build.
val tbsGeneratedAssets = layout.buildDirectory.dir("generated/tbs-assets")
val stageStreamCraft = tasks.register("stageStreamCraft") {
    val profileFile = file("src/main/assets/tbs/profile.json")
    val scDir = file(project.findProperty("streamcraftDir") as String? ?: "../../../StreamCraft/build/release")
    inputs.file(profileFile)
    inputs.dir(scDir).optional()
    outputs.dir(tbsGeneratedAssets)
    doLast {
        val profile = profileFile.readText()
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(profile)?.groupValues?.get(1)
            ?: error("tbs/profile.json has no \"$name\"")
        val ver = field("streamcraft")
        val mc = field("mc")
        val out = tbsGeneratedAssets.get().dir("tbs/streamcraft").asFile
        out.deleteRecursively()
        out.mkdirs()
        mapOf("arm64" to "android-arm64", "x86_64" to "android-x64").forEach { (abi, token) ->
            val src = File(scDir, "streamcraft-$token-$ver+mc$mc.jar")
            if (!src.isFile) error("StreamCraft $ver for MC $mc not found: $src (build it, or pass -PstreamcraftDir=)")
            src.copyTo(File(out, "streamcraft-android-$abi.jar"), overwrite = true)
        }
        // Line 1: the StreamCraft version. Line 2: a digest of the bundled jars, so a rebuilt jar with
        // the same version still re-stages on devices (UnpackSingleTask compares the whole file).
        val digest = MessageDigest.getInstance("SHA-256")
        out.listFiles { f -> f.extension == "jar" }!!.sortedBy { it.name }.forEach { digest.update(it.readBytes()) }
        File(out, "version").writeText(ver + "\n" + digest.digest().joinToString("") { "%02x".format(it) }.take(16))
        logger.lifecycle("[stageStreamCraft] StreamCraft $ver (MC $mc) staged")
    }
}
android.sourceSets["main"].assets.srcDir(tbsGeneratedAssets.get().asFile)
tasks.named("preBuild") { dependsOn(stageStreamCraft) }

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                afterEvaluate {
                    val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
                    task.inputs.property("lwjglArch", projectArch)
                    task.doLast {
                        val assetsDir = task.outputDir.get().asFile
                        val tag = "JREAssetsCleanup"
                        logger.lifecycle("[$tag] arch: $projectArch")
                        val jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")
                        jreList.forEach { jreVersion ->
                            val runtimeDir = File("$assetsDir/runtimes/$jreVersion")
                            logger.lifecycle("[$tag] runtimeDir: ${runtimeDir.absolutePath}")
                            runtimeDir.listFiles()?.forEach {
                                if (projectArch != "all" && it.name != "version" && !it.name.contains("universal") && it.name != "bin-$projectArch.tar.xz") {
                                    logger.lifecycle("[$tag] delete: $it : ${it.delete()}")
                                }
                            }
                        }

                        if (projectArch == "all") return@doLast
                        // TBS: keep only the target ABI's bundled StreamCraft jar.
                        File(assetsDir, "tbs/streamcraft").listFiles()?.forEach {
                            if (it.extension == "jar" && it.name != "streamcraft-android-$projectArch.jar") {
                                logger.lifecycle("[$tag] delete: $it : ${it.delete()}")
                            }
                        }
                        val abi = when (projectArch) {
                            "arm" -> "armeabi-v7a"
                            "arm64" -> "arm64-v8a"
                            "x86" -> "x86"
                            "x86_64" -> "x86_64"
                            else -> return@doLast
                        }
                        val lwjglVersions = file("libs").listFiles { f ->
                            f.name.matches(Regex("lwjgl-\\d+\\.\\d+\\.\\d+-natives-release\\.aar"))
                        }
                            ?.map { Regex("lwjgl-(\\d+\\.\\d+\\.\\d+)-natives-release\\.aar").find(it.name)!!.groupValues[1] }
                            ?: emptyList()
                        lwjglVersions.forEach { version ->
                            val nativesDir = File(assetsDir, "app_runtime/lwjgl/$version/natives")
                            if (nativesDir.isDirectory) {
                                nativesDir.listFiles()?.forEach { dir ->
                                    if (dir.isDirectory && dir.name != abi) {
                                        logger.lifecycle("Removing non-target-arch natives: $dir")
                                        dir.deleteRecursively()
                                    }
                                }
                            }
                        }
                    }
                }

                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    val baseName = "$launcherName-${if (variant.buildType == "release") launcherVersionName else "Debug-$launcherVersionName"}"
                    output.outputFileName = if (abi == "all") "$baseName.apk" else "$baseName-$abi.apk"
                }
            }
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

buildKeys {
    string("OAUTH_CLIENT_ID", getKeyFromLocal("OAUTH_CLIENT_ID", ".oauth_client_id.txt", defaultOAuthClientID), true)
    string("LAUNCHER_NAME", launcherAPPName, true)
    string("LAUNCHER_IDENTIFIER", launcherName, true)
    string("LAUNCHER_SHORT_NAME", launcherShortName, true)
    string("URL_HOME", launcherUrl, true)
    string("CURSEFORGE_API", getKeyFromLocal("CURSEFORGE_API_KEY", ".curseforge_api.txt", defaultCurseForgeApiKey), true)
    string("BUILD_ARCH", projectArch)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    //TBS capture bridge (Phase 4-5): CameraX webcam + LifecycleService host
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.nav3)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.material.color.utilities)
    implementation(libs.materialKolor)
    implementation(libs.reorderable)
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui)
    implementation(libs.richtext.ui.material3)
    implementation(platform(libs.editor.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    //Project
    implementation(project(":LayerController"))
    implementation(project(":ColorPicker"))
    implementation(project(":CardGrid"))
    implementation(project(":Terracotta"))
    implementation(project(":InputMap"))
    implementation(project(":Guide"))
    //Utils
    implementation(libs.bytehook)
    implementation(libs.gson)
    implementation(libs.commons.io)
    implementation(libs.commons.codec)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zip4j)
    implementation(libs.okio)
    implementation(libs.okhttp)
    implementation(libs.ktor.http)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.minidns.hla)
    implementation(libs.toml4j)
    implementation(libs.maven.artifact)
    implementation(libs.mmkv)
    implementation(libs.fishnet)
    implementation(libs.process.phoenix)
    implementation(libs.lunarcalendar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    //Safe
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    //Support
    implementation(libs.proxy.client.android)
    //Hilt
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    //Test
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
