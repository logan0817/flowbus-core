import java.util.Properties
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    kotlin("jvm")
    alias(libs.plugins.vanniktech.maven.publish)
}

val localMetadata = Properties().apply {
    val propertiesFile = layout.projectDirectory.file("gradle.properties").asFile
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun metadataProperty(name: String) = providers.gradleProperty(name).orElse(
    providers.provider { localMetadata.getProperty(name) ?: error("Missing property: $name") }
)

val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.secretKeyRingFile").isPresent
val artifactGroup = metadataProperty("GROUP").get()
val artifactVersion = metadataProperty("VERSION_NAME").get()

group = artifactGroup
version = artifactVersion

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    coordinates(
        artifactGroup,
        "flowbus-core",
        artifactVersion
    )

    pom {
        name.set("FlowBus Core")
        description.set("Platform-neutral FlowBus core built on Kotlin Coroutines and Flow.")
        inceptionYear.set("2026")
        url.set(metadataProperty("POM_URL"))
        licenses {
            license {
                name.set(metadataProperty("POM_LICENSE_NAME"))
                url.set(metadataProperty("POM_LICENSE_URL"))
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(metadataProperty("POM_DEVELOPER_ID"))
                name.set(metadataProperty("POM_DEVELOPER_NAME"))
                email.set(metadataProperty("POM_DEVELOPER_EMAIL"))
            }
        }
        scm {
            url.set(metadataProperty("POM_SCM_URL"))
            connection.set(metadataProperty("POM_SCM_CONNECTION"))
            developerConnection.set(metadataProperty("POM_SCM_DEV_CONNECTION"))
        }
    }

    publishToMavenCentral()
    if (hasSigningKey) {
        signAllPublications()
    }
}


