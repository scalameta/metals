import java.io.File
import sbt.Keys._
import sbt._

object InputProperties extends AutoPlugin {
  var file: Option[File] = None
  def resourceGenerator(
      input: Reference,
      input3: Reference
  ): Def.Initialize[Task[Seq[File]]] =
    Def.taskDyn {
      file.synchronized {
        file match {
          case Some(value) if value.isFile =>
            Def.task(List(value))
          case _ =>
            val baseInput = resourceGeneratorImpl(input, "metals-input")
            val scala3Input = resourceGeneratorImpl(input3, "metals-input3")
            baseInput.zipWith(scala3Input)((a, b) =>
              Seq(a, b).join.map(_.flatten)
            )
        }
      }
    }
  def resourceGeneratorImpl(
      input: Reference,
      resourceName: String
  ): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      val out = managedResourceDirectories
        .in(Compile)
        .value
        .head / s"$resourceName.properties"
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
