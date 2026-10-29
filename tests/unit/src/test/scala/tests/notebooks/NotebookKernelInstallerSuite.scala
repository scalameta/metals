package tests.notebooks

import scala.meta.internal.metals.notebook.NotebookKernelInstaller
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class NotebookKernelInstallerSuite extends BaseSuite {

  // The id hashes the absolute path, which has a drive letter on Windows.
  override def munitIgnore: Boolean = isWindows

  private def path(parts: String*): AbsolutePath =
    AbsolutePath(java.nio.file.Paths.get("/workspace", parts: _*))

  private def id(path: AbsolutePath) =
    NotebookKernelInstaller.kernelIdFor(path)

  test("same-name-in-different-directories") {
    assertNoDiff(id(path("a", "foo.ipynb")), "metals-foo-b8495f3b0c71d6b4")
    assertNoDiff(id(path("b", "foo.ipynb")), "metals-foo-35b1a7a29eb051b4")
  }

  test("paths-differing-only-in-case") {
    assertNoDiff(id(path("a", "Foo.ipynb")), "metals-foo-0099facbb1f237ab")
    assertNoDiff(id(path("a", "foo.ipynb")), "metals-foo-b8495f3b0c71d6b4")
  }

  test("valid-kernelspec-name") {
    assertNoDiff(
      id(path("A b", "My notebook!.ipynb")),
      "metals-my_notebook_-1432949e1219534c",
    )
  }

  test("deep-path-stays-short") {
    val deep = path((1 to 50).map(i => s"dir$i") :+ "a.ipynb": _*)
    assert(id(deep).length <= 100, id(deep))
  }
}
