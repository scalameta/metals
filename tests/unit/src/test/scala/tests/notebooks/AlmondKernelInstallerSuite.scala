package tests.notebooks

import java.nio.file.Paths

import scala.meta.internal.metals.notebook.AlmondKernelInstaller

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

  private def spec(classpath: String*) =
    AlmondKernelInstaller.kernelSpecJson("Scala (a)", argv(classpath: _*))

  test("kernel-json") {
    val json = spec("b", "a.jar", "b")
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
         |    "b",
         |    "--extra-class-path",
         |    "a.jar",
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

  test("same-spec-ignores-classpath-order") {
    assert(
      AlmondKernelInstaller.sameSpec(spec("b", "a.jar"), spec("a.jar", "b"))
    )
  }

  test("same-spec-detects-classpath-changes") {
    assert(!AlmondKernelInstaller.sameSpec(spec("a.jar"), spec("a.jar", "b")))
    assert(!AlmondKernelInstaller.sameSpec(spec("a.jar"), spec("c.jar")))
  }
}
