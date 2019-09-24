package scala.meta.internal.sbtmetals

import java.io.File
import java.{util => ju}

object BuildInfo {
  lazy val props: ju.Properties = {
    val p = new ju.Properties()
    val path = "sbt-metals-buildinfo.json"
    val in = this.getClass.getClassLoader.getResourceAsStream(path)
    assert(in != null, s"no such resource: $path")
    try p.load(in)
    finally in.close()
    p
  }
  def scalametaVersion: String = props.getProperty("scalametaVersion")
  def supportedScalaVersions: List[String] =
    props.getProperty("supportedScalaVersions").split(File.pathSeparator).toList
}
