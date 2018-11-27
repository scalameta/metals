import java.io.File
import sbt.Keys._
import sbt._

object InputProperties extends AutoPlugin {
  var file: Option[File] = None
  def resourceGenerator(input: Reference): Def.Initialize[Task[Seq[File]]] =
    Def.taskDyn {
      file.synchronized {
        file match {
          case Some(value) if value.isFile =>
            Def.task(List(value))
          case _ =>
            resourceGeneratorImpl(input)
        }
      }
    }
  def resourceGeneratorImpl(input: Reference): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      val out = managedResourceDirectories
        .in(Compile)
        .value
        .head / "metals-input.properties"
      val props = new java.util.Properties()
      props.put(
        "sourceroot",
        baseDirectory.in(ThisBuild).value.toString
      )
      val sourceJars = for {
        configurationReport <- updateClassifiers.in(input).value.configurations
        moduleReport <- configurationReport.modules
        (artifact, file) <- moduleReport.artifacts
        if artifact.classifier.contains("sources")
      } yield file
      props.put(
        "dependencySources",
        sourceJars.map(_.toPath).distinct.mkString(File.pathSeparator)
      )
      props.put(
        "sourceDirectories",
        List(
          unmanagedSourceDirectories.in(input, Compile).value,
          unmanagedSourceDirectories.in(input, Test).value
        ).flatten.mkString(File.pathSeparator)
      )
      props.put(
        "classpath",
        fullClasspath
          .in(input, Test)
          .value
          .map(_.data)
          .mkString(File.pathSeparator)
      )
      IO.write(props, "input", out)
      file = Some(out)
      List(out)
    }

}
