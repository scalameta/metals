package tests.notebooks

import scala.meta.internal.metals.notebook.NotebookKernelInstaller
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class NotebookKernelInstallerSuite extends BaseSuite {

  private def path(parts: String*): AbsolutePath =
    AbsolutePath(java.nio.file.Paths.get("/workspace", parts: _*))

  private val almondVersion = "0.15.0"

  test("same-named notebooks in different directories get different ids") {
    val a =
      NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"), almondVersion)
    val b =
      NotebookKernelInstaller.kernelIdFor(path("b", "foo.ipynb"), almondVersion)
    assertNotEquals(a, b)
  }

  test("the same notebook always gets the same id") {
    val first =
      NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"), almondVersion)
    val second =
      NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"), almondVersion)
    assertEquals(first, second)
  }

  test("different Almond versions get different ids") {
    val a =
      NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"), "0.14.5")
    val b =
      NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"), "0.15.0")
    assertNotEquals(a, b)
  }

  test("sanitizes characters that aren't valid in a kernelspec id") {
    val id = NotebookKernelInstaller.kernelIdFor(
      path("a b", "my notebook!.ipynb"),
      almondVersion,
    )
    assert(
      id.matches("metals-[A-Za-z0-9_-]+"),
      s"expected only filesystem-safe characters, got '$id'",
    )
  }

  test(
    "a literal underscore in the path can't collide with a sanitized separator"
  ) {
    val withSlash =
      NotebookKernelInstaller.kernelIdFor(
        path("a", "b", "x.ipynb"),
        almondVersion,
      )
    val withUnderscore =
      NotebookKernelInstaller.kernelIdFor(path("a_b", "x.ipynb"), almondVersion)
    assertNotEquals(withSlash, withUnderscore)
  }

  test("the id stays traceable back to the notebook's own path") {
    val id =
      NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"), almondVersion)
    assert(
      id.contains("foo"),
      s"expected the notebook's own filename to appear in the id, got '$id'",
    )
  }
}
