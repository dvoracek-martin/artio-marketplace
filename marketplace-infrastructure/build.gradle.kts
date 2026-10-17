plugins { id("io.spring.dependency-management") }
dependencies {
    implementation(project(":marketplace-domain"))
    implementation(project(":marketplace-application"))
    implementation(project(":marketplace-contract"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-health")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
}
