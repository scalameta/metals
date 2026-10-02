package tests.notebooks

import scala.meta.internal.metals.notebook.NotebookKernelInstaller
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class NotebookKernelInstallerSuite extends BaseSuite {

  private def path(parts: String*): AbsolutePath =
    AbsolutePath(java.nio.file.Paths.get("/workspace", parts: _*))

  test("same-named notebooks in different directories get different ids") {
    val a = NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"))
    val b = NotebookKernelInstaller.kernelIdFor(path("b", "foo.ipynb"))
    assertNotEquals(a, b)
  }

  test("the same notebook always gets the same id") {
    val first = NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"))
    val second = NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"))
    assertEquals(first, second)
  }

  test("sanitizes characters that aren't valid in a kernelspec id") {
    val id =
      NotebookKernelInstaller.kernelIdFor(path("a b", "my notebook!.ipynb"))
    assert(
      id.matches("metals-[A-Za-z0-9_-]+"),
      s"expected only filesystem-safe characters, got '$id'",
    )
  }

  test("the id stays traceable back to the notebook's own path") {
    val id = NotebookKernelInstaller.kernelIdFor(path("a", "foo.ipynb"))
    assert(
      id.contains("foo"),
      s"expected the notebook's own filename to appear in the id, got '$id'",
    )
  }
}
