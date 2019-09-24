import sbt._
import sbt.Keys._
import java.nio.file.Files
import java.nio.charset.StandardCharsets

object CiMatrixPlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {
    lazy val ciMatrix = settingKey[Map[String, String]](
      "Settings to describe the CI build matrix."
    )
  }
  import autoImport._

  override lazy val globalSettings = List(
    ciMatrix := Map.empty,
    TaskKey[Unit]("generateCiMatrix") := {
      val cross: Map[String, String] = ciMatrix.in(ThisBuild).value
      val base = baseDirectory.in(ThisBuild).value
      val ci = base / "ci"
      Files.createDirectories(ci.toPath)
      val out = ci / "build.json"
      val json = cross
        .map { case (k, v) => s""""$k": $v""" }
        .mkString("{\n  ", ",\n  ", "\n}")
      Files.write(out.toPath, json.getBytes(StandardCharsets.UTF_8))
      println(s"write: $out")
    }
  )

}
