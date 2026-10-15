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
}
