inThisBuild(Vector(
  scalaVersion := "2.12.7",
  scalacOptions ++= List(
    "-Yrangepos",
    "-Ywarn-unused"
  ),
))

libraryDependencies ++= List(
  "org.typelevel" %% "cats-core" % "1.4.0",
  "org.typelevel" %% "cats-core" % "1.4.0",
  "com.lihaoyi" %% "ujson" % "0.6.5",
  "com.lihaoyi" %% "sourcecode" % "0.1.4",
  "org.scalameta" %% "scalameta" % "4.0.0",
  "org.scalatest" %% "scalatest" % "3.0.5"
)
