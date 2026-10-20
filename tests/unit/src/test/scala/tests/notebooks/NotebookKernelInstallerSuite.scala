package tests.notebooks

import scala.meta.internal.metals.notebook.NotebookKernelInstaller
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class NotebookKernelInstallerSuite extends BaseSuite {

  private def path(parts: String*): AbsolutePath =
    AbsolutePath(java.nio.file.Paths.get("/workspace", parts: _*))

  private def id(path: AbsolutePath) =
    NotebookKernelInstaller.kernelIdFor(path)

  test("same-name-in-different-directories") {
    assertNotEquals(id(path("a", "foo.ipynb")), id(path("b", "foo.ipynb")))
  }

  test("stable") {
    assertEquals(id(path("a", "foo.ipynb")), id(path("a", "foo.ipynb")))
  }

  test("valid-kernelspec-name") {
    val kernelId = id(path("A b", "My notebook!.ipynb"))
    assert(
      kernelId.matches("metals-[a-z0-9._-]+"),
      s"expected a lower-case kernelspec name, got '$kernelId'",
    )
  }

  test("paths-differing-only-in-case") {
    assertNotEquals(id(path("a", "Foo.ipynb")), id(path("a", "foo.ipynb")))
  }

  test("traceable-to-file-name") {
    assert(id(path("a", "foo.ipynb")).startsWith("metals-foo-"))
  }

  test("deep-path-stays-short") {
    val deep = path((1 to 50).map(i => s"dir$i") :+ "a.ipynb": _*)
    assert(id(deep).length <= 100, id(deep))
  }
}
