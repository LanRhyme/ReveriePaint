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
        minSdk = 23
        targetSdk = 33
        versionCode = 2
        versionName = "1.0.0-Preview1"

        buildConfigField("String", "AIFADIAN_API_TOKEN", "\"$aifadianApiToken\"")
        buildConfigField("String", "AIFADIAN_USER_ID", "\"$aifadianUserId\"")

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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
