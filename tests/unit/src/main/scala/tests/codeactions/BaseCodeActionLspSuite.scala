package tests.codeactions

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.CodeAction
import tests.BaseLspSuite
import tests.FileLayout

abstract class BaseCodeActionLspSuite(
    suiteName: String
) extends BaseLspSuite(suiteName) {

  protected val scalaVersion: String = V.scala213

  def checkNoAction(
      name: TestOptions,
      input: String,
      scalafixConf: String = "",
      scalacOptions: List[String] = Nil,
      fileName: String = "A.scala",
      filterAction: CodeAction => Boolean = _ => true,
  )(implicit loc: Location): Unit = {
    val fileContent = input.replace("<<", "").replace(">>", "")
    check(
      name,
      input,
      "",
      fileContent,
      scalafixConf = scalafixConf,
      scalacOptions = scalacOptions,
      fileName = fileName,
      filterAction = filterAction,
    )
  }

  protected def toPath(fileName: String): String =
    s"a/src/main/scala/a/$fileName"
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
      fileName: String = "A.scala",
      changeFile: String => String = identity,
      expectError: Boolean = false,
      filterAction: CodeAction => Boolean = _ => true,
      overrideLayout: Option[String] = None,
      retryAction: Int = 0,
      assume: () => Boolean = () => true,
  )(implicit loc: Location): Unit = {
    val scalacOptionsJson =
      if (scalacOptions.nonEmpty)
        s""""scalacOptions": ["${scalacOptions.mkString("\",\"")}"],"""
      else ""
    val path = toPath(fileName)

    val layout = overrideLayout.getOrElse {
      s"""/metals.json
         |{"a":{$scalacOptionsJson "scalaVersion" : "$scalaVersion"}}
         |$scalafixConf
         |/$path
         |$input""".stripMargin
    }

    checkEdit(
      name,
      layout,
      expectedActions,
      expectedCode,
      selectedActionIndex,
      expectNoDiagnostics,
      kind,
      configuration,
      renamePath,
      extraOperations,
      changeFile,
      expectError,
      filterAction,
      retryAction,
      assume,
    )
  }

  def checkEdit(
      name: TestOptions,
      layout: String,
      expectedActions: String,
      expectedCode: String,
      selectedActionIndex: Int = 0,
      expectNoDiagnostics: Boolean = true,
      kind: List[String] = Nil,
      configuration: => Option[String] = None,
      renamePath: Option[String] = None,
      extraOperations: => Unit = (),
      changeFile: String => String = identity,
      expectError: Boolean = false,
      filterAction: CodeAction => Boolean = _ => true,
      retryAction: Int = 0,
      assumeFunc: () => Boolean = () => true,
  )(implicit loc: Location): Unit = {
    val files = FileLayout.mapFromString(layout)
    val (path, input) = files
      .find(f => f._2.contains("<<") && f._2.contains(">>"))
      .getOrElse {
        throw new IllegalArgumentException(
          "No `<< >>` was defined that specifies cursor position"
        )
      }
    val newPath = renamePath.getOrElse(path)
    val fullInput = layout.replace("<<", "").replace(">>", "")
    val actualExpectedCode =
      if (renamePath.nonEmpty) input.replace("<<", "").replace(">>", "")
      else expectedCode

    def assertActionsWithRetry(
        retry: Int = retryAction
    ): Future[List[CodeAction]] = {
      server
        .assertCodeAction(
          path,
          changeFile(input),
          expectedActions,
          kind,
          filterAction = filterAction,
        )
        .recoverWith {
          case _: Throwable if retry > 0 =>
            Thread.sleep(1000)
            assertActionsWithRetry(retry - 1)
          case _: Throwable if expectError => Future.successful(Nil)
        }
    }
    test(name) {
      assume(assumeFunc())
      cleanWorkspace()
      for {
        _ <- initialize(fullInput)
        _ <- server.didOpen(path)
        _ <- {
          configuration match {
            case Some(conf) => server.didChangeConfiguration(conf)
            case None => Future {}
          }
        }
        _ <- server.didChange(
          path,
          changeFile(input).replace("<<", "").replace(">>", ""),
        )
        codeActions <- assertActionsWithRetry()
        _ <- client.applyCodeAction(selectedActionIndex, codeActions, server)
        _ <- server.didSave(newPath) { _ =>
          if (newPath != path)
            server.toPath(newPath).readText
          else
            server.bufferContents(newPath)
        }
        _ = assertNoDiff(server.bufferContents(newPath), actualExpectedCode)
        _ = if (expectNoDiagnostics) assertNoDiagnostics() else ()
        _ = extraOperations
      } yield ()
    }
  }

}
