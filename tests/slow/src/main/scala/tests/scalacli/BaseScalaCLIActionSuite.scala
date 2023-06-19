package tests.scalacli

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.CodeAction
import tests.codeactions.BaseCodeActionLspSuite

class BaseScalaCLIActionSuite(name: String)
    extends BaseCodeActionLspSuite(name) {

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
      changeFile: String => String = identity,
      expectError: Boolean = false,
      filterAction: CodeAction => Boolean = _ => true,
  )(implicit loc: Location): Unit = {

    val path = toPath(fileName)
    val layout = Some(
      s"""/.bsp/scala-cli.json
         |${BaseScalaCliSuite.scalaCliBspJsonContent(scalaCliOptions)}
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
      changeFile,
      expectError,
      filterAction,
      overrideLayout = layout,
    )
  }
}
