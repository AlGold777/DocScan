plugins {
    kotlin("jvm")
    id("app.cash.sqldelight")
}

kotlin {
    jvmToolchain(21)
}

sqldelight {
    databases {
        create("DocScanDatabase") {
            packageName.set("app.docscan.core.db")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

dependencies {
    implementation("app.cash.sqldelight:runtime:2.3.2")
    implementation("org.jsoup:jsoup:1.18.3")
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("app.cash.sqldelight:sqlite-driver:2.3.2")
}

