plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val pinnedAndroidNdkVersion = "27.2.12479018"
val pinnedRustVersion = "1.99.0"

android {
    namespace = "org.nightknight.scale.tv"
    compileSdk = 36
    ndkVersion = pinnedAndroidNdkVersion

    defaultConfig {
        applicationId = "org.nightknight.scale.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-prototype"

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val repositoryRoot = rootProject.projectDir.parentFile
val generatedRustJniLibs = layout.buildDirectory.dir("generated/rustJniLibs")
val rustBuildScript = repositoryRoot.resolve("android/scripts/build-rust-native.sh")
val rustCoreSources = fileTree(repositoryRoot.resolve("crates/nightknight-render-core")) {
    include("Cargo.toml", "src/**/*.rs")
}

android.sourceSets.getByName("main").jniLibs.srcDir(generatedRustJniLibs)

val buildRustRenderCore = tasks.register<Exec>("buildRustRenderCore") {
    inputs.files(
        repositoryRoot.resolve("Cargo.toml"),
        repositoryRoot.resolve("Cargo.lock"),
        rustCoreSources,
    )
    inputs.file(rustBuildScript)
    inputs.file(project.buildFile)
    inputs.file(repositoryRoot.resolve("rust-toolchain.toml"))
    inputs.property("androidNdkVersion", pinnedAndroidNdkVersion)
    inputs.property("rustVersion", pinnedRustVersion)
    outputs.dir(generatedRustJniLibs)
    commandLine(
        "bash",
        rustBuildScript.absolutePath,
        generatedRustJniLibs.get().asFile.absolutePath,
        pinnedAndroidNdkVersion,
        pinnedRustVersion,
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRustRenderCore)
}
