import sbt._
import sbt.Keys._
import ujson._
import java.io.File
import java.nio.charset.StandardCharsets
import scala.collection.mutable

object BuildInfoResourcePlugin extends AutoPlugin {
  object autoImport {
    lazy val buildInfoResourceValue =
      taskKey[String]("A JSON object to generate")
    lazy val buildInfoResourceFilename =
      settingKey[String]("The name of the JSON file to generate")
    def buildInfoResourceJson(value: ujson.Value): String = {
      ujson.write(value, indent = 4)
    }
  }
  import autoImport._
  override def requires = sbt.plugins.JvmPlugin

  private val cache = mutable.Map.empty[String, File]
  override lazy val projectSettings = List(
    buildInfoResourceFilename := thisProject.value.id + "-buildinfo.json",
    resourceGenerators.in(Compile) += Def.taskDyn[Seq[File]] {
      cache.synchronized {
        val key = thisProject.value.id + scalaVersion.value
        cache.get(key) match {
          case Some(value) if value.isFile => Def.task(List(value))
          case _ => generateResource(key)
        }
      }
    }
  )

  def generateResource(key: String): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      val out =
        managedResourceDirectories.in(Compile).value.head /
          buildInfoResourceFilename.value
      val text = buildInfoResourceValue.value
      IO.write(out, text.getBytes(StandardCharsets.UTF_8))
      cache(key) = out
      List(out)
    }
}
