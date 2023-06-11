import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.21"
    application
}
group = "pt.isel.pc"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("pt.isel.pc.problemsets.set3.base.AppKt")
}

repositories {
    mavenCentral()
}

val ktlint: Configuration by configurations.creating

dependencies {

    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:2.0.0-alpha7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    // Kotlin test framework
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")

    ktlint("com.pinterest:ktlint:0.48.2") {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

val ktlintFormat by tasks.register("ktlintFormat", JavaExec::class) {
    group = "formatting"
    description = "Fix Kotlin code style deviations."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    // see https://pinterest.github.io/ktlint/install/cli/#command-line-usage for more information
    args("-F", "src/**/*.kt", "**.kts", "!**/build/**")
}

val outputDir = "${project.buildDir}/reports/ktlint/"
val inputFiles = project.fileTree(mapOf("dir" to "src", "include" to "**/*.kt"))
val ktlintCheck by tasks.creating(JavaExec::class) {
    inputs.files(inputFiles)
    outputs.dir(outputDir)
    description = "Check Kotlin code style."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = listOf("src/**/*.kt")
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}

tasks.named("build") {
    dependsOn("installDist")
}

tasks.named("test") {
    dependsOn("installDist")
}