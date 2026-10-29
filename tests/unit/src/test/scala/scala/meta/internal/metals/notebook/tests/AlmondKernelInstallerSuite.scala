package scala.meta.internal.metals.notebook.tests

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.notebook.AlmondKernelInstaller
import scala.meta.io.AbsolutePath

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

  private val dataDir = FunFixture[Path](
    _ => Files.createTempDirectory("metals-jupyter"),
    dir => RecursivelyDelete(AbsolutePath(dir)),
  )

  private def installer(dataDir: Path) =
    new AlmondKernelInstaller(() => dataDir, _ => Nil)

  private def stagingEntries(dataDir: Path): List[Path] = {
    val staging = dataDir.resolve("metals-kernel-staging")
    if (Files.isDirectory(staging))
      staging.toFile.listFiles().map(_.toPath).toList
    else Nil
  }

  dataDir.test("install-writes-kernel-json") { dir =>
    val almond = installer(dir)
    assert(!almond.exists("k"))
    almond.install("k", spec("a.jar"))
    assert(almond.exists("k"))
    assertNoDiff(
      Files.readString(
        dir.resolve("kernels").resolve("k").resolve("kernel.json")
      ),
      spec("a.jar").toString,
    )
    assertEquals(stagingEntries(dir), Nil)
  }

  dataDir.test("install-replaces-previous-kernel") { dir =>
    val almond = installer(dir)
    almond.install("k", spec("a.jar"))
    almond.install("k", spec("b.jar"))
    assert(almond.isInstalled("k", spec("b.jar")))
    assert(!almond.isInstalled("k", spec("a.jar")))
    assertEquals(stagingEntries(dir), Nil)
  }

  dataDir.test("install-keeps-other-kernels") { dir =>
    val almond = installer(dir)
    almond.install("k1", spec("a.jar"))
    almond.install("k2", spec("b.jar"))
    assert(almond.isInstalled("k1", spec("a.jar")))
    assert(almond.isInstalled("k2", spec("b.jar")))
  }

  dataDir.test("is-installed-ignores-classpath-order") { dir =>
    val almond = installer(dir)
    almond.install("k", spec("a.jar", "b"))
    assert(almond.isInstalled("k", spec("b", "a.jar")))
  }

  dataDir.test("is-installed-without-kernel") { dir =>
    assert(!installer(dir).isInstalled("k", spec("a.jar")))
  }

  dataDir.test("is-installed-with-unreadable-kernel-json") { dir =>
    val kernelDir = Files.createDirectories(dir.resolve("kernels").resolve("k"))
    Files.writeString(kernelDir.resolve("kernel.json"), "{not json")
    val almond = installer(dir)
    assert(almond.exists("k"))
    assert(!almond.isInstalled("k", spec("a.jar")))
    almond.install("k", spec("a.jar"))
    assert(almond.isInstalled("k", spec("a.jar")))
  }

  dataDir.test("kernel-spec-uses-the-resolved-launcher") { dir =>
    val almond = new AlmondKernelInstaller(
      () => dir,
      version => List(Paths.get(s"launcher-$version.jar")),
    )
    val argv = almond
      .kernelSpec(None, "1.2.3", "3.3.7", Nil, "Scala (a)")("argv")
      .arr
      .map(_.str)
    assertEquals(
      argv.slice(1, 4).toList,
      List("-cp", "launcher-1.2.3.jar", "almond.launcher.Launcher"),
    )
  }

  // Relative paths, so the expected paths are the same on Windows.
  private def jupyterDataDir(
      env: Map[String, String] = Map.empty,
      isMac: Boolean = false,
      isWin: Boolean = false,
  ): Path =
    AlmondKernelInstaller.jupyterDataDir(
      env.get,
      Paths.get("home"),
      isMac,
      isWin,
    )

  test("data-dir-from-jupyter-data-dir") {
    val env = Map("JUPYTER_DATA_DIR" -> "custom", "XDG_DATA_HOME" -> "xdg")
    assertEquals(jupyterDataDir(env), Paths.get("custom"))
    assertEquals(jupyterDataDir(env, isMac = true), Paths.get("custom"))
    assertEquals(jupyterDataDir(env, isWin = true), Paths.get("custom"))
  }

  test("data-dir-on-mac") {
    assertEquals(
      jupyterDataDir(Map("XDG_DATA_HOME" -> "xdg"), isMac = true),
      Paths.get("home", "Library", "Jupyter"),
    )
  }

  test("data-dir-on-windows") {
    assertEquals(
      jupyterDataDir(Map("APPDATA" -> "appdata"), isWin = true),
      Paths.get("appdata", "jupyter"),
    )
    assertEquals(
      jupyterDataDir(isWin = true),
      Paths.get("home", "AppData", "Roaming", "jupyter"),
    )
  }

  test("data-dir-on-linux") {
    assertEquals(
      jupyterDataDir(Map("XDG_DATA_HOME" -> "xdg")),
      Paths.get("xdg", "jupyter"),
    )
    assertEquals(
      jupyterDataDir(),
      Paths.get("home", ".local", "share", "jupyter"),
    )
  }

  test("data-dir-ignores-empty-variables") {
    val env =
      Map("JUPYTER_DATA_DIR" -> "", "XDG_DATA_HOME" -> "", "APPDATA" -> "")
    assertEquals(
      jupyterDataDir(env),
      Paths.get("home", ".local", "share", "jupyter"),
    )
    assertEquals(
      jupyterDataDir(env, isWin = true),
      Paths.get("home", "AppData", "Roaming", "jupyter"),
    )
  }
}
