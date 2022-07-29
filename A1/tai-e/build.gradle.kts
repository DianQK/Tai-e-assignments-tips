import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("java")
    id("application")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("lib/tai-e-assignment.jar"))
    implementation(files("../../lib/dependencies.jar"))
    testImplementation("junit:junit:4.13")
}

application {
    mainClass.set("pascal.taie.Assignment")
}

tasks.compileJava { options.encoding = "UTF-8" }
tasks.compileTestJava { options.encoding = "UTF-8" }

tasks.test {
    useJUnit()
    maxHeapSize = "4G"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val libDir = project.projectDir.parentFile.parentFile.resolve("lib")
libDir.listFiles()
    ?.map { it.name }
    ?.toList()
    ?.containsAll(listOf("dependencies.jar", "rt.jar"))
    ?.takeIf { it }
    ?: throw IllegalStateException("Could not find dependencies.jar or rt.jar in ${libDir.absolutePath}")

// https://docs.gradle.org/current/userguide/tutorial_using_tasks.html
tasks.register("submit") {
    doLast {
        val submittedFilenames = arrayOf<String>("LiveVariableAnalysis.java", "Solver.java", "IterativeSolver.java")
        val taieDir = project.projectDir.resolve("src/main/java/pascal/taie")
        val submittedFiles = taieDir.walk()
            .filter { it.isFile }
            .filter { submittedFilenames.contains(it.name) }
        val output = project.projectDir.resolve("output")
        val submittedZip = output.resolve(project.projectDir.parentFile.name + ".zip")
        if (submittedZip.exists()) {
            submittedZip.delete()
        }
        // https://stackoverflow.com/questions/46222055/create-a-zip-file-in-kotlin
        ZipOutputStream(BufferedOutputStream(FileOutputStream(submittedZip))).use { out ->
            for (file in submittedFiles) {
                FileInputStream(file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)
                        origin.copyTo(out, 1024)
                    }
                }
            }
        }
    }
}

