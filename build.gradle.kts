plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.2.1" apply false
    jacoco
}

allprojects {
    group = "io.artio.marketplace"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "jacoco")
    apply(plugin = "io.spring.dependency-management")
    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports { mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES) }
    }
    java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release = 25 }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    dependencies { "testRuntimeOnly"("org.junit.platform:junit-platform-launcher") }
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java { googleJavaFormat("1.28.0").reorderImports(true) }
    }
    tasks.withType<JacocoReport>().configureEach { reports { xml.required.set(true); html.required.set(true) } }
}

project(":marketplace-domain") {
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules { rule { limit { minimum = "0.40".toBigDecimal() } } }
    }
    tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
}

tasks.register("checkArchitecture") {
    group = "verification"
    description = "Checks the dependency direction between the marketplace modules."
    doLast {
        val forbidden = listOf("org.springframework", "java.sql", "org.postgresql", "com.github.docker", "io.artio.marketplace.infrastructure")
        val source = project(":marketplace-domain").file("src/main/java").walkTopDown().filter { it.isFile && it.extension == "java" }
            .flatMap { it.readLines().asSequence() }.filter { line -> forbidden.any(line::contains) }.toList()
        check(source.isEmpty()) { "Domain boundary violation: ${source.joinToString()}" }
    }
}

tasks.named("check") { dependsOn("checkArchitecture") }
