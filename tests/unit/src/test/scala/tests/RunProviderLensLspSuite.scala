package tests

import scala.meta.internal.metals.InitializationOptions

class RunProviderLensLspSuite extends BaseCodeLensLspSuite("runCodeLenses") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      TestingServer.TestDefault
        .copy(debuggingProvider = Option(false), runProvider = Option(true))
    )

  check("main")(
    """|package foo
       |<<run>>
       |object Main {
       |  def main(args: Array[String]): Unit = {}
       |}
       |""".stripMargin
  )

}
