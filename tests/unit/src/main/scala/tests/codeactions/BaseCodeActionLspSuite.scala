package tests.codeactions

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}

import munit.Location
import munit.TestOptions
import tests.BaseLspSuite

abstract class BaseCodeActionLspSuite(suiteName: String)
    extends BaseLspSuite(suiteName) {

  protected val scalaVersion: String = V.scala212

  def checkNoAction(
      name: TestOptions,
      input: String
  ): Unit = {
    val fileContent = input.replace("<<", "").replace(">>", "")
    check(name, input, "", fileContent)
  }

  def check(
      name: TestOptions,
      input: String,
      expectedActions: String,
      expectedCode: String,
      selectedActionIndex: Int = 0,
      expectNoDiagnostics: Boolean = true,
      kind: List[String] = Nil,
      scalafixConf: String = "",
      scalacOptions: List[String] = Nil,
      configuration: => Option[String] = None,
      scalaVersion: String = scalaVersion,
      renamePath: Option[String] = None,
      extraOperations: => Unit = (),
      fileName: String = "A.scala"
  )(implicit loc: Location): Unit = {
    val scalacOptionsJson =
      s""""scalacOptions": ["${scalacOptions.mkString("\",\"")}"]"""
    val path = s"a/src/main/scala/a/$fileName"
    val newPath = renamePath.getOrElse(path)
    val fileContent = input.replace("<<", "").replace(">>", "")
    val actualExpectedCode =
      if (renamePath.nonEmpty) fileContent else expectedCode

    test(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(s"""/metals.json
                                  |{"a":{$scalacOptionsJson, "scalaVersion" : "$scalaVersion"}}
                                  |$scalafixConf
                                  |/$path
                                  |$fileContent""".stripMargin)
        _ <- server.didOpen(path)
        _ <- {
          configuration match {
            case Some(conf) => server.didChangeConfiguration(conf)
            case None => Future {}
          }
        }
        codeActions <-
          server.assertCodeAction(path, input, expectedActions, kind)
        _ <- client.applyCodeAction(selectedActionIndex, codeActions, server)
        _ <- server.didSave(newPath) { _ =>
          server.toPath(newPath).readText
        }
        _ = assertNoDiff(server.bufferContents(newPath), actualExpectedCode)
        _ = if (expectNoDiagnostics) assertNoDiagnostics() else ()
        _ = extraOperations
      } yield ()
    }
  }

}
