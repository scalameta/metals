package tests.scalacli

import scala.meta.internal.metals.scalacli.ScalaCli

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.CodeAction
import tests.codeactions.BaseCodeActionLspSuite

class BaseScalaCLIActionSuite(name: String)
    extends BaseCodeActionLspSuite(name) {

  def checkNoActionScalaCLI(
      name: TestOptions,
      input: String,
      expectNoDiagnostics: Boolean = true,
      kind: List[String] = Nil,
      scalafixConf: String = "",
      scalacOptions: List[String] = Nil,
      scalaCliOptions: List[String] = Nil,
      configuration: => Option[String] = None,
      scalaVersion: String = scalaVersion,
      renamePath: Option[String] = None,
      extraOperations: => Unit = (),
      fileName: String = "A.scala",
      createInSrcDir: Boolean = true,
      changeFile: String => String = identity,
      expectError: Boolean = false,
      filterAction: CodeAction => Boolean = _ => true,
  ): Unit =
    checkScalaCLI(
      name = name,
      input = input,
      expectedActions = "",
      expectedCode = changeFile(input).replace("<<", "").replace(">>", ""),
      expectNoDiagnostics = expectNoDiagnostics,
      kind = kind,
      scalaCliOptions = scalaCliOptions,
      configuration = configuration,
      scalaVersion = scalaVersion,
      scalafixConf = scalafixConf,
      scalacOptions = scalacOptions,
      renamePath = renamePath,
      extraOperations = extraOperations,
      fileName = fileName,
      createInSrcDir = createInSrcDir,
      changeFile = changeFile,
      expectError = expectError,
      filterAction = filterAction,
    )

  def checkScalaCLI(
      name: TestOptions,
      input: String,
      expectedActions: String,
      expectedCode: String,
      selectedActionIndex: Int = 0,
      expectNoDiagnostics: Boolean = true,
      kind: List[String] = Nil,
      scalafixConf: String = "",
      scalacOptions: List[String] = Nil,
      scalaCliOptions: List[String] = Nil,
      configuration: => Option[String] = None,
      scalaVersion: String = scalaVersion,
      renamePath: Option[String] = None,
      extraOperations: => Unit = (),
      fileName: String = "A.scala",
      createInSrcDir: Boolean = true,
      changeFile: String => String = identity,
      expectError: Boolean = false,
      filterAction: CodeAction => Boolean = _ => true,
      retryAction: Int = 0,
  )(implicit loc: Location): Unit = {

    val path = toPath(fileName)
    val layout = Some(
      s"""/.bsp/scala-cli.json
         |${ScalaCli.scalaCliBspJsonContent(scalaCliOptions)}
         |/.scala-build/ide-inputs.json
         |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
         |/$path
         |$input""".stripMargin
    )
    super.check(
      name,
      input,
      expectedActions,
      expectedCode,
      selectedActionIndex,
      expectNoDiagnostics,
      kind,
      scalafixConf,
      scalacOptions,
      configuration,
      scalaVersion,
      renamePath,
      extraOperations,
      fileName,
      createInSrcDir,
      changeFile,
      expectError,
      filterAction,
      layout,
      retryAction,
    )
  }
}
