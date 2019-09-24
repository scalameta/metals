import sbt._
import sbt.Keys._
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.util.Properties
import scala.sys.process._
import java.net.URL
import scala.util.control.NonFatal

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
  }
  import autoImport._

  override lazy val globalSettings = List(
    githubActionsWorkflow := ujson.Obj(),
    githubActionsCheck := {
      githubActionsGenerate.value
      val exit = List("git", "diff", ".github/workflows", "--exit-code").!
      require(
        exit == 0,
        "build is not in sync with the CI matrix. " +
          "Run 'sbt githubActionsGenerate' to fix this problem."
      )
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
