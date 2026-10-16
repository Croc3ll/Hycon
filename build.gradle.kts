plugins {
    java
    id("com.azuredoom.hytale-tools") version "1.+"
}

group = project.property("group").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
}

hytaleTools {
    javaVersion = property("java_version").toString().toInt()
    hytaleVersion = property("hytale_version").toString()
    manifestServerVersion = property("manifestServerVersion").toString()
    manifestGroup = property("manifest_group").toString()
    modId = property("mod_id").toString()
    modDescription = property("mod_description").toString()
    modUrl = property("mod_url").toString()
    mainClass = property("main_class").toString()
    modCredits = property("mod_author").toString()
    manifestDependencies = property("manifest_dependencies").toString()
    manifestOptionalDependencies = property("manifest_opt_dependencies").toString()
    curseforgeId = property("curseforgeID").toString()
    disabledByDefault = property("disabled_by_default").toString().toBoolean()
    includesPack = property("includes_pack").toString().toBoolean()
    patchline = property("patchline").toString()
    injectServerJavadocsIntoSources = property("injectServerJavadocsIntoSources").toString().toBoolean()
    generateAssetsBinary = property("generateAssetsBinary").toString().toBoolean()
    property("hytaleHomeOverride").toString().takeIf { file(it).exists() }?.let { hytaleHomeOverride = it }
}

repositories {
    mavenCentral()
}

// No bundled libraries: the web server is the JDK's own (com.sun.net.httpserver) and JSON is
// a small hand-written reader/writer, so the jar stays tiny and has nothing to shade.

// The dev server writes Hycon's data (admins, audit log, config) into src/main/resources
// (its data folder links there). Keep that out of the released jar.
tasks.named<ProcessResources>("processResources") {
    exclude("hycon.json", "admins.json", "audit.log", "players.json", "FIRST-LOGIN.txt", "*.tmp")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(project.property("mod_name").toString())
    archiveVersion.set(project.property("version").toString())
}
