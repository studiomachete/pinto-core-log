import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("maven-publish")
}

android {
    namespace = "com.music961.pintocore.log"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

// Bug Hunt R01 A3 fix — Room schema 를 라이브러리 자체 schemas 디렉토리로 export.
// PintoLogDatabase exportSchema=true 와 짝.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AWS SDK BOM (pinto-core-aws 가 api 로 노출하는 dynamodb 등 transitive 버전 정합)
    implementation(platform("aws.sdk.kotlin:bom:1.6.57"))

    // pinto-core-aws (LogUploader 가 dynamoBatchWriteItem 사용)
    implementation("com.music961:pinto-core-aws:3.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Room (메인 앱 정합 2.8.4)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Lifecycle (ProcessLifecycleOwner) - 메인 앱 정합 2.10.0
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")

    // WorkManager - 메인 앱 정합 2.11.2
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Hilt - 메인 앱 정합 2.59.2
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("androidx.work:work-testing:2.11.2")
}

// GitHub Packages 배포 설정
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.music961"
            artifactId = "pinto-core-log"
            version = "0.1.1"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Pinto Core Log")
                description.set("Pinto 로그 시스템 코어 라이브러리 (Android, iOS 포팅 대비)")
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/studiomachete/pinto-core-log")
            credentials {
                username = System.getenv("GITHUB_USERNAME") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.token") as String?
            }
        }
    }
}
