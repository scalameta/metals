package tests

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.doctor.DoctorStatus

class DoctorCompilationStatusLspSuite
    extends BaseLspSuite("doctor-compilation-status") {

  private def compilationStatuses(): Map[String, DoctorStatus] =
    server.headServer.doctor
      .buildTargetsJson()
      .targets
      .getOrElse(Nil)
      .map(target => target.name -> target.compilationStatus)
      .toMap

  test("best-effort-dependency") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": { "scalaVersion": "${BuildInfo.latestScala3Next}" },
            |  "b": {
            |    "scalaVersion": "${BuildInfo.latestScala3Next}",
            |    "dependsOn": [ "a" ]
            |  }
            |}
            |/a/src/main/scala/a/A.scala
            |package a
            |object A:
            |  def foo: Int = 1
            |/b/src/main/scala/b/B.scala
            |package b
            |object B:
            |  val x: Int = a.A.foo
            |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ = assertEquals(
        compilationStatuses(),
        Map("a" -> DoctorStatus.check, "b" -> DoctorStatus.check),
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("def foo: Int = 1", "def foo: Int = \"error\"")
      )
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = assertEquals(
        compilationStatuses(),
        Map("a" -> DoctorStatus.error, "b" -> DoctorStatus.alert),
      )
    } yield ()
  }
}
