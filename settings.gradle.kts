val projects = arrayOf("A1", "A2", "A3", "A4", "A5", "A6")
for (project in projects) {
    include(project)
    project(":$project").projectDir = file("$project/tai-e")
}
