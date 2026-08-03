import java.io.File
import sbt.Keys._
import sbt._

object InputProperties extends AutoPlugin {
  def resourceGenerator(
      input: Reference,
      input3: Reference,
  ): Def.Initialize[Task[Seq[File]]] =
    Def.taskDyn {
      val baseInput = resourceGeneratorImpl(input, "metals-input")
      val scala3Input = resourceGeneratorImpl(input3, "metals-input3")
      baseInput.zipWith(scala3Input)((a, b) => Seq(a, b).join.map(_.flatten))
    }
  def resourceGeneratorImpl(
      input: Reference,
      resourceName: String,
  ): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      val out =
        (Compile / managedResourceDirectories).value.head / s"$resourceName.properties"
      val props = new java.util.Properties()
      props.put(
        "sourceroot",
        (ThisBuild / baseDirectory).value.toString,
      )
      val sourceJars = for {
        configurationReport <- (input / updateClassifiers).value.configurations
        moduleReport <- configurationReport.modules
        (artifact, file) <- moduleReport.artifacts
        if artifact.classifier.contains("sources")
      } yield file
      props.put(
        "dependencySources",
        sourceJars.map(_.toPath).distinct.mkString(File.pathSeparator),
      )
      props.put(
        "sourceDirectories",
        List(
          (input / Compile / unmanagedSourceDirectories).value,
          (input / Test / unmanagedSourceDirectories).value,
        ).flatten.mkString(File.pathSeparator),
      )
      props.put(
        "classpath",
        (input / Test / fullClasspath).value
          .map(_.data)
          .mkString(File.pathSeparator),
      )
      props.put(
        "semanticdbTargets",
        List(
          (input / Compile / semanticdbTargetRoot).value,
          (input / Test / semanticdbTargetRoot).value,
        ).mkString(File.pathSeparator),
      )
      IO.write(props, "input", out)
      List(out)
    }

}
