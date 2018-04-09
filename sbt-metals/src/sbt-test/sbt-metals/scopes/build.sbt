lazy val a = project
lazy val b = project

lazy val c = project.aggregate(a, b)
