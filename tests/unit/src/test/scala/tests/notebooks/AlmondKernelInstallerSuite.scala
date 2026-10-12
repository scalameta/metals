package tests.notebooks

import java.nio.file.Paths

import scala.meta.internal.metals.notebooks.AlmondKernelInstaller

import tests.BaseSuite

class AlmondKernelInstallerSuite extends BaseSuite {

  // Relative paths, so the expected JSON is the same on Windows.
  private def argv(classpath: String*) =
    AlmondKernelInstaller.argv(
      "java",
      List(Paths.get("launcher.jar")),
      "2.13.18",
      classpath.map(Paths.get(_)).toList,
    )

  test("kernel-json") {
    val json =
      AlmondKernelInstaller.kernelSpecJson("Scala (a)", argv("a.jar", "b"))
    assertNoDiff(
      ujson.write(json, indent = 2),
      """|{
         |  "argv": [
         |    "java",
         |    "-cp",
         |    "launcher.jar",
         |    "almond.launcher.Launcher",
         |    "--scala",
         |    "2.13.18",
         |    "--extra-class-path",
         |    "a.jar",
         |    "--extra-class-path",
         |    "b",
         |    "--connection-file",
         |    "{connection_file}"
         |  ],
         |  "display_name": "Scala (a)",
         |  "language": "scala",
         |  "env": {}
         |}
         |""".stripMargin,
    )
  }

  test("argv-ignores-classpath-order-and-duplicates") {
    assertEquals(argv("b", "a.jar", "b"), argv("a.jar", "b"))
  }
}
