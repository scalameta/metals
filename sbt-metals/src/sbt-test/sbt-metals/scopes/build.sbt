lazy val a = project
lazy val b = project

lazy val root = project.aggregate(a, b)
