package tests

import java.nio.file.Files
import java.nio.file.Paths

import scala.util.Try

import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsInputBoxParams
import scala.meta.internal.metals.MetalsInputBoxResult
import scala.meta.internal.metals.ServerCommands
import scala.meta.io.AbsolutePath

import munit.TestOptions
import org.apache.commons.io.FileUtils
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.ShowMessageRequestParams

class NewProjectLspSuite extends BaseLspSuite("new-project") {

  override def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default
        .copy(inputBoxProvider = true, openNewWindowProvider = true)
    )

  def scalatestTemplate(name: String = "scalatest-example"): String =
    s"""|/$name/build.sbt
        |lazy val root = (project in file(".")).
        |  settings(
        |    inThisBuild(List(
        |      organization := "com.example",
        |      scalaVersion := "2.13.1"
        |    )),
        |    name := "scalatest-example"
        |  )
        |
        |libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.0" % Test
        |
        |
        |/$name/project/build.properties
        |sbt.version=1.3.10
        |
        |
        |/$name/src/main/scala/CubeCalculator.scala
        |object CubeCalculator extends App {
        |  def cube(x: Int) = {
        |    x * x * x
        |  }
        |}
        |
        |/$name/src/test/scala/CubeCalculatorTest.scala
        |class CubeCalculatorTest extends org.scalatest.FunSuite {
        |  test("CubeCalculator.cube") {
        |    assert(CubeCalculator.cube(3) === 27)
        |  }
        |}
        |
        |""".stripMargin

  check("basic-template")(
    pickedProject = Some("scala/scalatest-example.g8"),
    name = None,
    expectedContent = scalatestTemplate()
  )

  check("custom-name")(
    pickedProject = Some("scala/scalatest-example.g8"),
    name = Some("my-custom-name"),
    expectedContent = scalatestTemplate("my-custom-name")
  )

  check("custom-template")(
    pickedProject = None,
    name = Some("my-custom-name"),
    customTemplate = Some("scala/scalatest-example.g8"),
    expectedContent = scalatestTemplate("my-custom-name")
  )

  check("website-template")(
    pickedProject = None,
    name = None,
    templateFromg8Site = Some("scala/scalatest-example.g8"),
    expectedContent = scalatestTemplate()
  )

  test("template-regex") {
    val text =
      """|# Templates:
         |- [jimschubert/finatra.g8](https://github.com/jimschubert/finatra.g8)
         |(A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager)
         |
         |Some other text
         |""".stripMargin

    NewProjectProvider.templatePattern
      .findAllIn(text)
      .matchData
      .foreach {
        case matching =>
          assert(matching.groupCount == 2)
          assertNoDiff(matching.group(1), "jimschubert/finatra.g8")
          assertNoDiff(
            matching.group(2),
            "A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager"
          )

      }

  }

  private def check(testName: TestOptions)(
      pickedProject: Option[String],
      name: Option[String],
      expectedContent: String,
      customTemplate: Option[String] = None,
      templateFromg8Site: Option[String] = None
  ): Unit =
    test(testName) {

      val tmpDirectory =
        AbsolutePath(Files.createTempDirectory("metals")).dealias

      def isSelectProject(params: ShowMessageRequestParams): Boolean =
        params.getMessage() == Messages.NewScalaProject.selectTheTemplate

      def isEnterTemplate(params: MetalsInputBoxParams): Boolean =
        params.prompt == Messages.NewScalaProject.enterG8Template

      def isOpenWindow(params: ShowMessageRequestParams): Boolean =
        params.getMessage() == Messages.NewScalaProject
          .askForNewWindowParams()
          .getMessage()

      def isChooseName(params: MetalsInputBoxParams): Boolean =
        params.prompt == Messages.NewScalaProject.enterName

      def onSelectProject(findAction: String => Option[MessageActionItem]) = {
        if (customTemplate.isDefined) {
          findAction(NewProjectProvider.custom.id)
        } else if (templateFromg8Site.isDefined) {
          findAction(NewProjectProvider.more.id)
            .orElse(findAction(templateFromg8Site.get))
        } else {
          findAction(pickedProject.getOrElse(""))
        }
      }

      def onDirectorySelect(
          params: ShowMessageRequestParams,
          findAction: String => Option[MessageActionItem]
      ) = {
        val file =
          Try(Paths.get(params.getMessage())).map(AbsolutePath.apply).toOption
        file.flatMap { path =>
          if (path == tmpDirectory) {
            val res = findAction("ok")
            res
          } else {
            val tmpRoot = tmpDirectory.root
            if (tmpRoot == path.root) {
              val next =
                tmpDirectory.toRelative(path).toNIO.iterator().next().filename
              findAction(next)
            } else {
              findAction(tmpRoot.get.toString()).orElse(findAction(".."))
            }

          }
        }
      }

      client.showMessageRequestHandler = { params =>
        val findAction = { (name: String) =>
          params.getActions().asScala.find(_.getTitle() == name)
        }
        if (isSelectProject(params)) {
          onSelectProject(findAction)
        } else if (isOpenWindow(params)) {
          findAction("yes")
        } else {
          onDirectorySelect(params, findAction)
        }
      }

      client.inputBoxHandler = { params =>
        if (isChooseName(params)) {
          Some(
            new MetalsInputBoxResult(value = name.getOrElse(params.value))
          )
        } else if (isEnterTemplate(params)) {
          Some(
            new MetalsInputBoxResult(value = customTemplate.get)
          )
        } else {
          None
        }
      }

      def ignoreVersions(text: String) =
        text.replaceAll("\\d+\\.\\d+\\.\\d+", "1.0.0").replace("\r\n", "\n")

      def directoryOutput(dir: AbsolutePath) = {
        dir.listRecursive.toList
          .sortBy(_.toString())
          .collect {
            case file if (file.isFile) =>
              s"""|/${file
                .toRelative(tmpDirectory)
                .toString()
                .replace('\\', '/')}
                  |${file.readText}
                  |""".stripMargin
          }
          .mkString("\n")
      }

      val testFuture = for {
        _ <- server.initialize(s"""
                                  |/metals.json
                                  |{
                                  |  "a": { }
                                  |}
                                  |""".stripMargin)
        _ <-
          server
            .executeCommand(
              ServerCommands.NewScalaProject.id
            )
        output = directoryOutput(tmpDirectory)
      } yield assertDiffEqual(
        ignoreVersions(output),
        ignoreVersions(expectedContent),
        // note(@tgodzik) The template is pretty stable for last couple of years except for versions.
        "This test is based on https://github.com/scala/scalatest-example.g8, it might have changed."
      )

      testFuture.onComplete {
        case _ =>
          FileUtils.deleteDirectory(tmpDirectory.toNIO.toFile())
      }
      testFuture
    }

}
