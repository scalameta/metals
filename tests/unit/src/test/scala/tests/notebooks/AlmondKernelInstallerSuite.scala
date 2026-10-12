package tests.notebooks

import java.nio.file.Paths

import scala.meta.internal.metals.notebooks.AlmondKernelInstaller

import tests.BaseSuite

class AlmondKernelInstallerSuite extends BaseSuite {

  private val classpath = List(Paths.get("/a.jar"), Paths.get("/b.jar"))

  test("kernel.json has the shape Jupyter expects") {
    // Relative paths, so the expected JSON is the same on Windows.
    val json = AlmondKernelInstaller.kernelSpecJson(
      "Scala (a)",
      List("java", "-cp", "launcher.jar", "almond.launcher.Launcher"),
      List(Paths.get("a.jar"), Paths.get("b.jar")),
    )
    assertNoDiff(
      ujson.write(json, indent = 2),
      """|{
         |  "argv": [
         |    "java",
         |    "-cp",
         |    "launcher.jar",
         |    "almond.launcher.Launcher",
         |    "--connection-file",
         |    "{connection_file}"
         |  ],
         |  "display_name": "Scala (a)",
         |  "language": "scala",
         |  "env": {},
         |  "metadata": {
         |    "classpath": [
         |      "a.jar",
         |      "b.jar"
         |    ]
         |  }
         |}
         |""".stripMargin,
    )
  }

  test("isUpToDateJson accepts its own kernelSpecJson output") {
    val json =
      AlmondKernelInstaller.kernelSpecJson("Scala (a)", List("java"), classpath)
    assert(AlmondKernelInstaller.isUpToDateJson(json, classpath))
  }

  test("isUpToDateJson rejects a changed classpath") {
    val json =
      AlmondKernelInstaller.kernelSpecJson("Scala (a)", List("java"), classpath)
    val changedClasspath = classpath :+ Paths.get("/new.jar")
    assert(!AlmondKernelInstaller.isUpToDateJson(json, changedClasspath))
  }

  test("isUpToDateJson ignores classpath ordering") {
    val json =
      AlmondKernelInstaller.kernelSpecJson("Scala (a)", List("java"), classpath)
    assert(AlmondKernelInstaller.isUpToDateJson(json, classpath.reverse))
  }
}
