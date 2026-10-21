plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Gradle's own publishing (no extra dependency): the prebuilt package that hosts use.
    `maven-publish`
}

// Recording, storage, GPX and upload without any UI. The routelog app and other apps host it.
android {
    namespace = "io.github.markusnuo.routelog.library"
    // Lint enforces it: the library's resources must not clash with a host app's.
    resourcePrefix = "routelog_"
    // 37, not 37.2 like the app: a host needs no newer platform than the library really uses
    // (the prebuilt package records this as the host's minimum compileSdk).
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // Hosts compile against the prebuilt package with their own Kotlin compiler, which may be
    // older than this project's (AGP 9.3 brings 2.2, and a 2.2 compiler can't read classes built
    // for 2.4). So: language and API version 2.2, and the 2.2 standard library as dependency.
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
    coreLibrariesVersion = "2.2.0"
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Hosts take the library as a prebuilt package from maven-repo/ in this repository (a Git
// submodule there), not as sources: building the sources would tie them to this project's exact
// Android Gradle plugin version. `./gradlew :routelog-library:publishReleasePublicationToRepoRepository`
// writes the version in gradle.properties; see docs/LIBRARY.md.
android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

val libraryVersion = providers.gradleProperty("routelogLibraryVersion")
val libraryGroupPath = "io/github/markusnuo/routelog-library"

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.github.markusnuo"
            artifactId = "routelog-library"
            version = libraryVersion.get()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("routelog-library")
                description.set("Records a driven route with the phone's GPS, also with the screen locked, and uploads it as GPX.")
                url.set("https://github.com/MarkusNuo/routelog")
                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0 only")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
    repositories {
        // The committed package that hosts use.
        maven {
            name = "repo"
            url = uri(rootProject.layout.projectDirectory.dir("maven-repo"))
        }
        // Throw-away copy for verifyLibraryPackage; never committed.
        maven {
            name = "verify"
            url = uri(layout.buildDirectory.dir("verify-repo"))
        }
    }
}

// A published version never changes: "published" means "tagged"
// (routelog-library-<version>). Publishing to maven-repo/ fails when the tag exists, unless
// -PallowRepublish=true. Without git the check is skipped with a warning.
val libraryTag = libraryVersion.map { "routelog-library-$it" }
val libraryTagLookup = libraryTag.flatMap { tag ->
    providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", "tag", "--list", tag)
        isIgnoreExitValue = true
    }.let { lookup ->
        lookup.result.zip(lookup.standardOutput.asText) { result, output ->
            if (result.exitValue == 0) output.lines().any { it.trim() == tag }.toString() else "unknown"
        }
    }
}
val allowRepublish = providers.gradleProperty("allowRepublish").map { it.toBoolean() }.orElse(false)

tasks.withType<PublishToMavenRepository>().matching { it.repository.name == "repo" }.configureEach {
    val version = libraryVersion
    val tag = libraryTag
    val lookup = libraryTagLookup
    val republish = allowRepublish
    doFirst {
        val tagState = try {
            lookup.get()
        } catch (e: Exception) {
            "unknown"
        }
        when {
            tagState == "unknown" ->
                logger.warn("WARNING: could not ask git whether tag ${tag.get()} exists; the published-version check is skipped.")
            tagState == "true" && republish.get() ->
                logger.warn("WARNING: version ${version.get()} is published (tag ${tag.get()}); republishing because of -PallowRepublish=true.")
            tagState == "true" ->
                throw GradleException("version ${version.get()} is published (tagged ${tag.get()}); raise routelogLibraryVersion")
        }
    }
}

// The package must not depend on the checkout: .gitattributes pins the library's sources to LF.
// A clone from before that keeps CRLF until routelog-library/src is checked out again, and
// publishing or verifying there would silently package other bytes (verify would then say "stale"
// for no visible reason). So both fail first, naming the cause.
val checkLibrarySourceLineEndings = tasks.register("checkLibrarySourceLineEndings") {
    group = "verification"
    description = "Fails if a file in routelog-library/src has CRLF line endings."
    // Captured at configuration time; the files are only walked when the task runs.
    val sources = layout.projectDirectory.dir("src")
    doLast {
        val root = sources.asFile
        val withCr = root.walkTopDown().filter { file ->
            file.isFile && file.readBytes().let { bytes ->
                // Like Git, a NUL byte in the first 8000 bytes means binary; such a file may hold 0x0D.
                val head = bytes.size.coerceAtMost(8000)
                (0 until head).none { bytes[it] == 0.toByte() } && bytes.any { it == '\r'.code.toByte() }
            }
        }.map { "routelog-library/src/" + it.relativeTo(root).invariantSeparatorsPath }.toList()
        if (withCr.isNotEmpty()) {
            throw GradleException(
                "routelog-library/src has CRLF line endings (a checkout from before .gitattributes pinned LF): " +
                    "delete routelog-library/src and run `git checkout -- routelog-library/src` " +
                    "(${withCr.size} files, e.g. ${withCr.take(3).joinToString()})",
            )
        }
    }
}
tasks.withType<PublishToMavenRepository>().matching { it.repository.name == "repo" }.configureEach {
    dependsOn(checkLibrarySourceLineEndings)
}

// Publishes into build/verify-repo and compares the package with the committed one: the .aar, the
// sources jar and the .module byte for byte, each with its checksum files (the library's sources
// are LF in every checkout, .gitattributes), the .pom apart from its line endings (Gradle writes
// it, like maven-metadata.xml, with those of the system; maven-metadata.xml holds a timestamp and
// is not compared). Byte equality is shown on the development PC (Windows) only, also from a fresh
// clone with core.autocrlf=true at another path. Other operating systems and JDKs are untested
// (the deflate implementation, file permissions and the system line separator might differ), so
// publish and verify on the development PC. Run it on the commit that gets tagged.
val cleanVerifyRepo = tasks.register<Delete>("cleanLibraryVerifyRepo") {
    delete(layout.buildDirectory.dir("verify-repo"))
}
tasks.withType<PublishToMavenRepository>().matching { it.repository.name == "verify" }.configureEach {
    dependsOn(cleanVerifyRepo, checkLibrarySourceLineEndings)
}
tasks.register("verifyLibraryPackage") {
    group = "verification"
    description = "Fails if maven-repo/ does not hold the package the current sources produce."
    dependsOn(checkLibrarySourceLineEndings)
    dependsOn(tasks.withType<PublishToMavenRepository>().matching { it.repository.name == "verify" })
    val version = libraryVersion
    val committed = rootProject.layout.projectDirectory.dir("maven-repo/$libraryGroupPath")
    val fresh = layout.buildDirectory.dir("verify-repo/$libraryGroupPath")
    doLast {
        val v = version.get()
        fun ByteArray.withoutCarriageReturns() = filter { it != '\r'.code.toByte() }.toByteArray()
        val checksums = listOf("md5", "sha1", "sha256", "sha512")
        // File names without the common "routelog-library-<version>" prefix.
        val exact = listOf(".aar", "-sources.jar", ".module").flatMap { suffix ->
            listOf(suffix) + checksums.map { "$suffix.$it" }
        }
        fun differs(suffix: String): Boolean {
            val name = "$v/routelog-library-$v$suffix"
            val a = committed.file(name).asFile
            val b = fresh.get().file(name).asFile
            return when {
                !a.isFile || !b.isFile -> true
                suffix == ".pom" -> !a.readBytes().withoutCarriageReturns().contentEquals(b.readBytes().withoutCarriageReturns())
                else -> !a.readBytes().contentEquals(b.readBytes())
            }
        }
        val differing = (exact + ".pom").filter { differs(it) }
        if (differing.isNotEmpty()) {
            throw GradleException(
                "maven-repo/ is stale: publish again or raise the version " +
                    "(version $v; differs or missing: ${differing.joinToString()})",
            )
        }
        logger.lifecycle(
            "maven-repo/ matches the sources (version $v: .aar, sources jar and .module identical " +
                "(with their checksum files), .pom identical apart from line endings).",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // The database and its entities are part of the library's API (recordings, uploads).
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Part of the API: Routelog exposes StateFlow, SharedFlow and Flow and has suspend functions, so
    // a host needs coroutines on its compile classpath. The Android artifact also provides
    // Dispatchers.Main, which RecordingService needs at runtime; a host app may not bring it.
    // Same version the routelog app resolves through AndroidX.
    api(libs.kotlinx.coroutines.android)
    // Reads a server's early answer (401, 413) that Android's HttpURLConnection loses when the
    // server stops reading the body.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
