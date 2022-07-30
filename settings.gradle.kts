val projects = arrayOf("A1", "A2")
for (project in projects) {
    include(project)
    project(":$project").projectDir = file("$project/tai-e")
}
