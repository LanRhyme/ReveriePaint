import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

// AGP 9.x has built-in Kotlin support - no org.jetbrains.kotlin.android plugin needed

// 预编译 jni 模式(默认): 使用 third_party/android-native-libs 里预编译的
// libreverie_jni.so + 全部 Krita/Qt/KF6 动态库, 无需本地 Qt/Krita 环境,
// 克隆即可构建 APK
// 强制重新编译 C++: ./gradlew assembleDebug -PbuildNative
//   (需要本地 Qt for Android 6.6.3 + Krita 源码 + KF6 头文件, 见 README)
val prebuiltJni = rootProject.file("third_party/android-native-libs/libreverie_jni.so").isFile
val buildNative = project.hasProperty("buildNative")
val usePrebuiltJni = prebuiltJni && !buildNative

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.isFile) {
    localProps.load(localPropsFile.inputStream())
}
val aifadianApiToken = (project.findProperty("AIFADIAN_API_TOKEN") as? String)
    ?: System.getenv("AIFADIAN_API_TOKEN")
    ?: localProps.getProperty("AIFADIAN_API_TOKEN", "")

val aifadianUserId = (project.findProperty("AIFADIAN_USER_ID") as? String)
    ?: System.getenv("AIFADIAN_USER_ID")
    ?: localProps.getProperty("AIFADIAN_USER_ID", "")

android {
    namespace = "com.reverie.paint"
    compileSdk = 36

    ndkVersion = "25.2.9519653"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.reverie.paint"
        // 测试分发包名: ./gradlew assembleRelease -PappIdSuffix=.beta
        // 指定后生成独立包名 (com.reverie.paint.beta) 的测试包, 与正式版同装互不影响;
        // 不指定时为 null, 默认构建行为完全不变
        applicationIdSuffix = project.findProperty("appIdSuffix") as? String
        if (project.hasProperty("appIdSuffix")) {
            versionNameSuffix = "-test"
        }
        minSdk = 23
        targetSdk = 33
        versionCode = 20
        versionName = "1.3.3"

        buildConfigField("String", "AIFADIAN_API_TOKEN", "\"$aifadianApiToken\"")
        buildConfigField("String", "AIFADIAN_USER_ID", "\"$aifadianUserId\"")

        // 无 adb 真机 A/B 用的"液化实验档位": ./gradlew assembleDebug -PlqTestProfile=<n>
        //   0(默认) = 不改任何默认行为(一切照旧由 debug property 控制)
        //   1 = 默认开 AGSL 预览 + latest-state-wins(2 步/帧)   ← 目标形态
        //   2 = 默认开引擎侧 CPU 预览 + latest-state-wins(2 步/帧)(2A-2 对照)
        //   3 = 默认开 AGSL 预览 + 不做 latest-state-wins(对照调度)
        // 只改"默认值": 任一项仍可被对应 property 覆盖(见 docs/RENDER-OPTIMIZATION.md §4.11)
        val lqTestProfile = (project.findProperty("lqTestProfile") as? String)?.toIntOrNull() ?: 0
        buildConfigField("int", "LQ_TEST_PROFILE", lqTestProfile.toString())

        // 液化预览"代理分辨率"百分比(无 adb 做 Proxy Resolution 实验): ./gradlew assembleDebug -PlqProxy=<10..100>
        //   100(默认) = 源纹理全分辨率(与历史行为逐像素一致)
        //   75/50/25  = 源纹理按比例下采样(几何不变, 纹理带宽/显存随之下降)
        // 运行时可用 `setprop debug.reverie.lqproxy <n>` 覆盖(见 docs/RENDER-OPTIMIZATION.md 实验 A)
        val lqProxyPercent =
            ((project.findProperty("lqProxy") as? String)?.toIntOrNull() ?: 100).coerceIn(10, 100)
        buildConfigField("int", "LQ_PROXY_PERCENT", lqProxyPercent.toString())

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        if (buildNative) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments +=
                        buildList {
                            add("-DANDROID_ABI=arm64-v8a")
                            add("-DCMAKE_BUILD_TYPE=Release")
                            // 追加自定义 CMake 参数, 例如:
                            // ./gradlew assembleDebug -PbuildNative -PcmakeArgs="-DQT_ANDROID_DIR=/opt/Qt6"
                            val cmakeArgs =
                                (project.findProperty("cmakeArgs") as? String)
                                    ?.split(" ")
                                    ?.filter { it.isNotBlank() }
                            if (cmakeArgs != null) addAll(cmakeArgs)
                        }
                }
            }
        }
    }

    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
        ?: (project.findProperty("ANDROID_KEYSTORE_PATH") as? String)
        ?: localProps.getProperty("ANDROID_KEYSTORE_PATH")

    val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
        ?: (project.findProperty("ANDROID_KEYSTORE_PASSWORD") as? String)
        ?: localProps.getProperty("ANDROID_KEYSTORE_PASSWORD")

    val keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
        ?: (project.findProperty("ANDROID_KEY_ALIAS") as? String)
        ?: localProps.getProperty("ANDROID_KEY_ALIAS")

    val keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
        ?: (project.findProperty("ANDROID_KEY_PASSWORD") as? String)
        ?: localProps.getProperty("ANDROID_KEY_PASSWORD")

    val hasReleaseSigning =
        !keystorePath.isNullOrEmpty() &&
        !keystorePassword.isNullOrEmpty() &&
        !keyAlias.isNullOrEmpty() &&
        !keyPassword.isNullOrEmpty() &&
        file(keystorePath).isFile

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            // Compress the native libs so the APK stays small on disk;
            // they're extracted (and decompressed) at install time.
            useLegacyPackaging = true
            pickFirsts += listOf("**/libreverie_jni.so", "**/*.so")
        }
    }
}

if (usePrebuiltJni) {
    val copyPrebuiltJniLibs by tasks.registering(Copy::class) {
        from(rootProject.file("third_party/android-native-libs"))
        into(file("src/main/jniLibs/arm64-v8a"))
        include("*.so")
    }

    tasks.matching {
        it.name.contains("NativeLibs") || it.name.contains("JniLibFolders")
    }.configureEach {
        dependsOn(copyPrebuiltJniLibs)
    }
}

dependencies {
    implementation(files("libs/Qt6Android.jar"))
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("dev.chrisbanes.haze:haze:1.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
