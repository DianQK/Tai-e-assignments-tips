import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

submit("A1") {
    add("LiveVariableAnalysis.java")
    add("Solver.java")
    add("IterativeSolver.java")
}

submit("A2") {
    add("ConstantPropagation.java")
    add("Solver.java")
    add("WorkListSolver.java")
}

submit("A3") {
    add("DeadCodeDetection.java")
}

submit("A4") {
    add("CHABuilder.java")
    add("InterConstantPropagation.java")
    add("InterSolver.java")
}

fun submit(name: String, setSubmittedFilenames: ArrayList<String>.() -> Unit) {
    // https://docs.gradle.org/current/userguide/tutorial_using_tasks.html
    tasks.register("submit$name") {
        doLast {
            val submittedFilenames = arrayListOf<String>()
            setSubmittedFilenames(submittedFilenames)
            val taieDir = project.projectDir.resolve("$name/tai-e/src/main/java/pascal/taie")
            val submittedFiles = taieDir.walk()
                .filter { it.isFile }
                .filter { submittedFilenames.contains(it.name) }
            val output = project.projectDir.resolve("output")
            output.mkdir()
            val submittedZip = output.resolve("$name.zip")
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
            println(submittedZip)
        }
    }
}
