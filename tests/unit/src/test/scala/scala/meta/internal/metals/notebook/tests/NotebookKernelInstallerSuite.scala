package scala.meta.internal.metals.notebook.tests

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.notebook.AlmondKernelInstaller
import scala.meta.internal.metals.notebook.NotebookKernelInstaller
import scala.meta.internal.metals.notebook.NotebookKernelInstaller.NotebookKernel
import scala.meta.internal.metals.notebook.NotebookProvider
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import tests.BaseSuite
import tests.TestingClient

class NotebookKernelInstallerSuite extends BaseSuite {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private def path(parts: String*): AbsolutePath =
    AbsolutePath(Paths.get("/workspace", parts: _*))

  private def assertId(path: AbsolutePath, expected: String): Unit = {
    assume(
      !isWindows,
      "the id hashes the absolute path, which has a drive letter on Windows",
    )
    assertNoDiff(NotebookKernelInstaller.kernelIdFor(path), expected)
  }

  test("same-name-in-different-directories") {
    assertId(path("a", "foo.ipynb"), "metals-foo-b8495f3b0c71d6b4")
    assertId(path("b", "foo.ipynb"), "metals-foo-35b1a7a29eb051b4")
  }

  test("paths-differing-only-in-case") {
    assertId(path("a", "Foo.ipynb"), "metals-foo-0099facbb1f237ab")
    assertId(path("a", "foo.ipynb"), "metals-foo-b8495f3b0c71d6b4")
  }

  test("valid-kernelspec-name") {
    assertId(
      path("A b", "My notebook!.ipynb"),
      "metals-my_notebook_-1432949e1219534c",
    )
  }

  test("deep-path-stays-short") {
    val deep = path((1 to 50).map(i => s"dir$i") :+ "a.ipynb": _*)
    val id = NotebookKernelInstaller.kernelIdFor(deep)
    assert(id.length <= 100, id)
  }

  private def messages(client: TestingClient): List[(MessageType, String)] =
    client.showMessages.asScala.map(m => m.getType -> m.getMessage).toList

  private def requests(client: TestingClient): List[String] =
    client.messageRequests.asScala.toList

  private case class Setup(
      client: TestingClient,
      almond: AlmondKernelInstaller,
      installer: NotebookKernelInstaller,
  )

  // With no reply, TestingClient fails on any prompt.
  private def setup(dataDir: Path, reply: Option[MessageActionItem]) = {
    val client = new TestingClient(AbsolutePath(dataDir), Buffers())
    client.showMessageRequestHandler = _ => reply
    val almond = new AlmondKernelInstaller(() => dataDir, _ => Nil)
    val buildTargets = BuildTargets.empty
    val provider =
      new NotebookProvider(Buffers(), client, () => ???, buildTargets)
    val installer = new NotebookKernelInstaller(
      provider,
      buildTargets,
      client,
      almond,
      () => None,
      () => None,
    )
    Setup(client, almond, installer)
  }

  private val dataDir = FunFixture[Path](
    _ => Files.createTempDirectory("metals-jupyter"),
    dir => RecursivelyDelete(AbsolutePath(dir)),
  )

  private val kernel = NotebookKernel("metals-k", "Scala (a)")

  private def spec(classpath: String*)(): ujson.Obj =
    AlmondKernelInstaller.kernelSpecJson(
      kernel.displayName,
      AlmondKernelInstaller.argv(
        "java",
        Nil,
        "3.3.7",
        classpath.map(Paths.get(_)).toList,
      ),
    )

  private def failingSpec(): ujson.Obj = throw new IllegalStateException("boom")

  private val installPrompt = Messages.InstallNotebookKernel.params().getMessage

  dataDir.test("no-build-target") { dir =>
    val Setup(client, _, installer) = setup(dir, reply = None)
    for {
      result <- installer.ensureKernel(
        path("a.ipynb"),
        askBeforeInstall = false,
      )
    } yield {
      assertEquals(result, None)
      assertEquals(
        messages(client),
        List(MessageType.Warning -> "No build target found for this notebook."),
      )
    }
  }

  dataDir.test("no-build-target-when-asking-is-silent") { dir =>
    val Setup(client, _, installer) = setup(dir, reply = None)
    for {
      result <- installer.ensureKernel(
        path("a.ipynb"),
        askBeforeInstall = true,
      )
    } yield {
      assertEquals(result, None)
      assertEquals(messages(client), Nil)
    }
  }

  dataDir.test("install-without-asking") { dir =>
    val Setup(client, almond, installer) = setup(dir, reply = None)
    for {
      result <- installer.ensure(
        kernel,
        spec("a.jar"),
        askBeforeInstall = false,
      )
    } yield {
      assertEquals(result, Some(kernel.copy(newlyInstalled = true)))
      assert(almond.isInstalled(kernel.kernelId, spec("a.jar")()))
      assertEquals(requests(client), Nil)
    }
  }

  dataDir.test("install-after-confirming") { dir =>
    val Setup(client, almond, installer) =
      setup(dir, reply = Some(Messages.InstallNotebookKernel.install))
    for {
      result <- installer.ensure(
        kernel,
        spec("a.jar"),
        askBeforeInstall = true,
      )
    } yield {
      assertEquals(result, Some(kernel.copy(newlyInstalled = true)))
      assert(almond.isInstalled(kernel.kernelId, spec("a.jar")()))
      assertEquals(requests(client), List(installPrompt))
    }
  }

  dataDir.test("declined-install") { dir =>
    val Setup(_, almond, installer) =
      setup(dir, reply = Some(Messages.notNow))
    for {
      result <- installer.ensure(
        kernel,
        spec("a.jar"),
        askBeforeInstall = true,
      )
    } yield {
      assertEquals(result, None)
      assert(!almond.exists(kernel.kernelId))
    }
  }

  dataDir.test("dismissed-install") { dir =>
    // The client answers null when the user closes the prompt.
    val Setup(_, almond, installer) =
      setup(dir, reply = Some(null))
    for {
      result <- installer.ensure(
        kernel,
        spec("a.jar"),
        askBeforeInstall = true,
      )
    } yield {
      assertEquals(result, None)
      assert(!almond.exists(kernel.kernelId))
    }
  }

  dataDir.test("up-to-date-kernel-is-kept") { dir =>
    val Setup(client, almond, installer) = setup(dir, reply = None)
    almond.install(kernel.kernelId, spec("a.jar", "b.jar")())
    for {
      result <- installer.ensure(
        kernel,
        spec("b.jar", "a.jar"),
        askBeforeInstall = true,
      )
    } yield {
      assertEquals(result, Some(kernel))
      assertEquals(requests(client), Nil)
    }
  }

  dataDir.test("stale-kernel-is-reinstalled-without-asking") { dir =>
    val Setup(client, almond, installer) = setup(dir, reply = None)
    almond.install(kernel.kernelId, spec("a.jar")())
    for {
      result <- installer.ensure(
        kernel,
        spec("b.jar"),
        askBeforeInstall = true,
      )
    } yield {
      assertEquals(result, Some(kernel.copy(newlyInstalled = true)))
      assert(almond.isInstalled(kernel.kernelId, spec("b.jar")()))
      assertEquals(requests(client), Nil)
    }
  }

  dataDir.test("failed-refresh-keeps-previous-kernel") { dir =>
    val Setup(client, almond, installer) = setup(dir, reply = None)
    almond.install(kernel.kernelId, spec("a.jar")())
    for {
      result <- installer.ensure(kernel, failingSpec, askBeforeInstall = true)
    } yield {
      assertEquals(result, Some(kernel))
      assert(almond.isInstalled(kernel.kernelId, spec("a.jar")()))
      assertEquals(
        messages(client),
        List(
          MessageType.Warning -> "Failed to update Jupyter kernel 'Scala (a)', it may run with an outdated classpath: boom"
        ),
      )
    }
  }

  dataDir.test("failed-install") { dir =>
    val Setup(client, almond, installer) = setup(dir, reply = None)
    for {
      result <- installer.ensure(
        kernel,
        failingSpec,
        askBeforeInstall = false,
      )
    } yield {
      assertEquals(result, None)
      assert(!almond.exists(kernel.kernelId))
      assertEquals(
        messages(client),
        List(
          MessageType.Error -> "Failed to install Jupyter kernel 'Scala (a)': boom"
        ),
      )
    }
  }
}
