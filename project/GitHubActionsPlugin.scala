import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.net.URL
import sbt._
import sbt.Keys._
import scala.sys.process._
import scala.util.Properties
import scala.util.control.NonFatal
import ujson._

object GitHubActionsPlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {
    lazy val githubActionsGenerate = taskKey[Unit]("Generate the CI matrix")
    lazy val githubActionsCheck = taskKey[Unit](
      "Task to check that the CI matrix is in sync with the build."
    )
    lazy val githubActionsWorkflow = settingKey[ujson.Obj](
      "Settings to describe the CI build matrix."
    )
    def githubActionsStep(
        name: String,
        run: String,
        matrix: Obj = Obj()
    ): Obj = {
      githubActionsSteps(List(name -> run), matrix)
    }
    def githubActionsSteps(
        namedSteps: List[(String, String)],
        matrix: Obj = Obj()
    ): Obj = {
      val steps =
        Obj("uses" -> Str("actions/checkout@v1")) +:
          Obj("uses" -> Str("olafurpg/setup-scala@v2")) +:
          namedSteps.map {
            case (name, run) =>
              Obj("name" -> Str(name), "run" -> Str(run))
          }
      val result = Obj(
        "runs-on" -> "ubuntu-latest",
        "steps" -> Arr(steps.value)
      )
      result.value ++= matrix.value
      result
    }
    def githubActionsMatrix(
        key: String,
        values: Seq[String],
        extra: (String, ujson.Value)*
    ) = Obj(
      "strategy" -> Obj.apply(
        "fail-fast" -> Bool(false),
        "matrix" -> Obj(key -> values.map(Str(_)), extra: _*)
      )
    )
    def githubActionsPartition(
        dir: File,
        partitionCount: Option[Int] = None
    ): List[String] = {
      val names = IO
        .listFiles(dir)
        .filter(_.isDirectory)
        .map(_.toPath.getFileName.toString)
        .sorted
      val count = partitionCount.getOrElse(names.length)
      require(names.nonEmpty, "no tests to partition!")
      val groupSizes = math.ceil(names.length.toDouble / count).toInt
      if (groupSizes == 1) names.toList
      else names.grouped(groupSizes).map(_.mkString("{", ",", "}")).toList
    }
  }
  import autoImport._

  override lazy val globalSettings = List(
    githubActionsWorkflow := ujson.Obj(),
    githubActionsCheck := {
      githubActionsGenerate.value
      val exit = List("git", "diff", "--exit-code", ".github/workflows").!
      if (exit != 0) {
        throw new RuntimeException(
          "build is not in sync with the CI matrix. " +
            "Run 'sbt githubActionsGenerate' to fix this problem."
        ) with sbt.internal.util.FeedbackProvidedException
      }
    },
    githubActionsGenerate := {
      val workflow = githubActionsWorkflow.in(ThisBuild).value
      val out = baseDirectory.in(ThisBuild).value /
        ".github" / "workflows" / "ci.yml"
      val yaml = "---\n" + ujson.write(workflow, indent = 4)
      Files.write(out.toPath, yaml.getBytes(StandardCharsets.UTF_8))
      println(s"write: $out")
    }
  )

}
