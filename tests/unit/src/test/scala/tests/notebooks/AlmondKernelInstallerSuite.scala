package tests.notebooks

import scala.meta.internal.metals.notebooks.AlmondKernelInstaller

import tests.BaseSuite

class AlmondKernelInstallerSuite extends BaseSuite {

  test("kernel.json has the shape Jupyter expects") {
    val json = AlmondKernelInstaller.kernelSpecJson(
      "Scala (a)",
      List("java", "-cp", "launcher.jar", "almond.launcher.Launcher"),
    )
    assertEquals(json("display_name").str, "Scala (a)")
    assertEquals(json("language").str, "scala")
    assertEquals(
      json("argv").arr.map(_.str).toList,
      List(
        "java", "-cp", "launcher.jar", "almond.launcher.Launcher",
        "--connection-file", "{connection_file}",
      ),
    )
    assert(json("env").obj.isEmpty)
  }
}
