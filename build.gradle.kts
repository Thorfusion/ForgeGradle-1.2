plugins {
    java
    idea
    eclipse
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.anatawa12.forge"

version = "1.2-${property("version")!!}"

base {
    archivesName.set("ForgeGradle")
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
    sourceCompatibility = JavaVersion.VERSION_1_8
}

val gradleStartDev = false

repositories {
    maven("https://maven.minecraftforge.net") {
        name = "forge"
    }
    mavenCentral()
    if (gradleStartDev) {
        maven("https://libraries.minecraft.net/") {
            name = "mojang"
        }
    }
}

val jar by tasks.getting(Jar::class) {
    manifest {
        attributes(mapOf(
            "version" to project.version,
            "javaCompliance" to project.java.targetCompatibility,
            "group" to project.group,
            "Implementation-Version" to "${project.version}${getGitHash()}"
        ))
    }
}

val deployerJars by configurations.creating

dependencies {
    implementation(gradleApi())

    // moved to the beginning to be the overrider
    implementation("org.ow2.asm:asm:9.4")
    implementation("org.ow2.asm:asm-tree:9.4")
    implementation("com.google.guava:guava:31.1-jre")

    implementation("net.sf.opencsv:opencsv:2.3") // reading CSVs.. also used by SpecialSource
    implementation("com.cloudbees:diff4j:1.3") // for difing and patching
    implementation("com.github.abrarsyed.jastyle:jAstyle:1.2") // formatting
    implementation("net.sf.trove4j:trove4j:2.1.0") // because its awesome.

    implementation("com.github.jponge:lzma-java:1.3") // replaces the LZMA binary
    implementation("com.nothome:javaxdelta:2.0.1") // GDIFF implementation for BinPatches
    implementation("com.google.code.gson:gson:2.9.1") // Used instead of Argo for building changelog.

    implementation("net.md-5:SpecialSource:1.11.0") // deobf and reobs

    // because curse
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    implementation("org.apache.httpcomponents:httpmime:4.5.13")

    // mcp stuff
    implementation("de.oceanlabs.mcp:RetroGuard:3.6.6")
    implementation("de.oceanlabs.mcp:mcinjector:3.2-SNAPSHOT")
    implementation("net.minecraftforge:Srg2Source:4.2.7")

    // pin jdt deps
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.14.0")
    implementation("org.eclipse.platform:org.eclipse.core.commands:3.10.200")
    implementation("org.eclipse.platform:org.eclipse.core.contenttype:3.8.200")
    implementation("org.eclipse.platform:org.eclipse.core.expressions:3.8.200")
    implementation("org.eclipse.platform:org.eclipse.core.filesystem:1.9.500")
    implementation("org.eclipse.platform:org.eclipse.core.jobs:3.13.100")
    implementation("org.eclipse.platform:org.eclipse.core.resources:3.18.0")
    implementation("org.eclipse.platform:org.eclipse.core.runtime:3.26.0")
    implementation("org.eclipse.platform:org.eclipse.equinox.app:1.6.200")
    implementation("org.eclipse.platform:org.eclipse.equinox.common:3.16.200")
    implementation("org.eclipse.platform:org.eclipse.equinox.preferences:3.10.100")
    implementation("org.eclipse.platform:org.eclipse.equinox.registry:3.11.200")
    implementation("org.eclipse.platform:org.eclipse.osgi:3.18.100")
    implementation("org.eclipse.platform:org.eclipse.text:3.12.200")
    implementation("org.osgi:org.osgi.service.prefs:1.1.2")
    implementation("org.osgi:osgi.annotation:8.0.1")

    // stupid maven
    deployerJars("org.apache.maven.wagon:wagon-ssh:3.5.1")

    //Stuff used in the GradleStart classes
    if (gradleStartDev) {
        compileOnly("com.mojang:authlib:1.5.16")
        compileOnly("net.minecraft:launchwrapper:1.11")
    }

    testImplementation("junit:junit:4.+")
}

val compileJava by tasks.getting(JavaCompile::class) {
    options.isDeprecation = true
    //options.compilerArgs += ["-Werror", "-Xlint:unchecked"]
}

val javadoc by tasks.getting(Javadoc::class) {
    // linked javadoc urls.. why not...

    classpath = classpath.filter { !(it.name == "main" && it.parentFile.name == "resources") } 
    val options = options as StandardJavadocDocletOptions
    options.links("https://gradle.org/docs/current/javadoc/")
    options.links("https://guava.dev/releases/18.0/api/docs/")
    options.links("https://asm.ow2.io/javadoc/")
}

java {
    withJavadocJar()
    withSourcesJar()
}

artifacts {
    archives(jar)
}

val test by tasks.getting(Test::class) {
    if (project.hasProperty("filesmaven")) // disable this test when on the forge jenkins
    {
        exclude("**/ExtensionMcpMappingTest*")
        exclude("**/ExtensionForgeVersionTest*")
    }
    reports {
        junitXml.required.set(true)
    }
}

publishing {
    publications {
        val bintray by this.creating(MavenPublication::class) {
            from(components["java"])
            artifactId = base.archivesName.get()

            pom {
                name.set(project.base.archivesName.get())
                description.set("Gradle plugin for Forge")
                url.set("https://github.com/anatawa12/ForgeGradle-1.2")

                scm {
                    url.set("https://github.com/anatawa12/ForgeGradle-1.2")
                    connection.set("scm:git:git://github.com/anatawa12/ForgeGradle-1.2.git")
                    developerConnection.set("scm:git:git@github.com:anatawa12/ForgeGradle-1.2.git")
                }

                issueManagement {
                    system.set("github")
                    url.set("https://github.com/anatawa12/ForgeGradle-1.2/issues")
                }

                licenses {
                    license {
                        name.set("Lesser GNU Public License, Version 2.1")
                        url.set("https://www.gnu.org/licenses/lgpl-2.1.html")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("AbrarSyed")
                        name.set("Abrar Syed")
                        roles.set(setOf("developer"))
                    }

                    developer {
                        id.set("LexManos")
                        name.set("Lex Manos")
                        roles.set(setOf("developer"))
                    }

                    developer {
                        id.set("anatawa12")
                        name.set("anatawa12")
                        roles.set(setOf("developer"))
                    }
                }
            }
        }
    }
}

gradlePlugin {
    isAutomatedPublishing = false
    plugins {
        create("curseforge") {
            id = "curseforge"
            implementationClass = "net.minecraftforge.gradle.curseforge.CursePlugin"
        }
        create("fml") {
            id = "fml"
            implementationClass = "net.minecraftforge.gradle.user.patch.FmlUserPlugin"
        }
        create("forge") {
            id = "forge"
            implementationClass = "net.minecraftforge.gradle.user.patch.ForgeUserPlugin"
        }
        create("launch4j") {
            id = "launch4j"
            implementationClass = "edu.sc.seis.launch4j.Launch4jPlugin"
        }
        create("liteloader") {
            id = "liteloader"
            implementationClass = "net.minecraftforge.gradle.user.lib.LiteLoaderPlugin"
        }
    }
}

// write out version so its convenient for doc deployment
file("build").mkdirs()
file("build/version.txt").writeText("$version")

fun getGitHash(): String {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(file("."))
        .start()
    process.waitFor()
    return "-" + (if (process.exitValue() != 0) "unknown" else process.inputStream.reader().use { it.readText() }.trim())
}
