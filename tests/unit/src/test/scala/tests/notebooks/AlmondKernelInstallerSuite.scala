package tests.notebooks

import java.nio.file.Paths

import scala.meta.internal.metals.notebooks.AlmondKernelInstaller

import tests.BaseSuite

class AlmondKernelInstallerSuite extends BaseSuite {

  private val classpath = List(Paths.get("/a.jar"), Paths.get("/b.jar"))
  private val almondVersion = "0.15.0"

  test("kernel.json has the shape Jupyter expects") {
    val json = AlmondKernelInstaller.kernelSpecJson(
      "Scala (a)",
      List("java", "-cp", "launcher.jar", "almond.launcher.Launcher"),
      almondVersion,
      classpath,
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

  test("isUpToDateJson accepts its own kernelSpecJson output") {
    val json = AlmondKernelInstaller.kernelSpecJson(
      "Scala (a)",
      List("java"),
      almondVersion,
      classpath,
    )
    assert(AlmondKernelInstaller.isUpToDateJson(json, almondVersion, classpath))
  }

  test("isUpToDateJson rejects a changed classpath") {
    val json = AlmondKernelInstaller.kernelSpecJson(
      "Scala (a)",
      List("java"),
      almondVersion,
      classpath,
    )
    val changedClasspath = classpath :+ Paths.get("/new.jar")
    assert(
      !AlmondKernelInstaller.isUpToDateJson(
        json,
        almondVersion,
        changedClasspath,
      )
    )
  }

  test("isUpToDateJson rejects a changed Almond version") {
    val json = AlmondKernelInstaller.kernelSpecJson(
      "Scala (a)",
      List("java"),
      almondVersion,
      classpath,
    )
    assert(!AlmondKernelInstaller.isUpToDateJson(json, "0.16.0", classpath))
  }
}
