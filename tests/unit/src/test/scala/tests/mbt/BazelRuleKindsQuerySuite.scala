package tests.mbt

import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.builds.WorkspaceLoadedStatus
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.CancelableFuture
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.mbt.importer.BazelMbtImporter
import scala.meta.internal.metals.mbt.importer.BazelQuery
import scala.meta.internal.metals.mbt.importer.MbtImport
import scala.meta.internal.metals.mbt.importer.MbtImportProvider
import scala.meta.internal.process.ExitCodes
import scala.meta.internal.process.ProcessOutput
import scala.meta.io.AbsolutePath

import munit.FunSuite
import org.eclipse.lsp4j.MessageParams

class BazelRuleKindsQuerySuite extends FunSuite {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private val expectedRuleKinds: String =
    "scala_library|java_library|scala_binary|java_binary|scala_test|java_test|scala_import|java_import"

  private case class StubResponse(
      exitCode: Int,
      stdout: List[String] = Nil,
      stderr: List[String] = Nil,
  )

  private class StubShellRunner(
      respond: (String, List[String]) => StubResponse
  ) extends ShellRunner(
        Time.system,
        EmptyWorkDoneProgress,
        () => UserConfiguration(),
      ) {
    val recordedArgs: ListBuffer[List[String]] = ListBuffer.empty[List[String]]

    override def run(
        commandRun: String,
        args: List[String],
        directory: AbsolutePath,
        redirectErrorOutput: Boolean,
        javaHome: Option[String],
        additionalEnv: Map[String, String] = Map.empty,
        processOut: ProcessOutput = ProcessOutput.Lines(_ => ()),
        processErr: String => Unit = _ => (),
        propagateError: Boolean = false,
        logInfo: Boolean = true,
    ): CancelableFuture[Int] = {
      recordedArgs.synchronized {
        recordedArgs += args
      }
      val resp = respond(commandRun, args)
      processOut match {
        case ProcessOutput.Lines(onLine) =>
          resp.stdout.foreach(onLine)
        case _ => ()
      }
      resp.stderr.foreach(processErr)
      CancelableFuture(Future.successful(resp.exitCode), Cancelable.empty)
    }
  }

  test("project-view-with-exclusions-produces-except-query") {
    val projectView =
      """|directories:
         |  .
         |
         |targets:
         |  //...:all
         |  -//excluded/...:all
         |""".stripMargin
    val patterns = BazelProjectViewTargets.parseFromContent(projectView)
    assertEquals(patterns, List("//...:all", "-//excluded/...:all"))
    assertEquals(
      BazelQuery.buildRuleKindsQuery(patterns).query,
      s"kind('$expectedRuleKinds', set(//...:all) except set(//excluded/...:all))",
    )
  }

  test("exclusions-only-defaults-includes-to-all-targets") {
    val patterns = List("-//excluded/...:all", "-//vendor/...:all")
    assertEquals(
      BazelQuery.buildRuleKindsQuery(patterns).query,
      s"kind('$expectedRuleKinds', set(//...) except set(//excluded/...:all //vendor/...:all))",
    )
  }

  test("empty-or-blank-patterns-defaults-to-all-targets") {
    assertEquals(
      BazelQuery.buildRuleKindsQuery(Nil).query,
      s"kind('$expectedRuleKinds', set(//...))",
    )
    assertEquals(
      BazelQuery.buildRuleKindsQuery(List("   ", "-")).query,
      s"kind('$expectedRuleKinds', set(//...))",
    )
  }

  test("quotes-special-characters-in-includes-and-excludes") {
    val patterns = List(
      "//core:name=value",
      "-//excluded:a1%3D=%3D%2Bagg",
    )
    assertEquals(
      BazelQuery.buildRuleKindsQuery(patterns).query,
      s"""kind('$expectedRuleKinds', set("//core:name=value") except set("//excluded:a1%3D=%3D%2Bagg"))""",
    )
  }

  test("query-run-fails-on-syntax-error-exit-code") {
    val workspace = AbsolutePath(Files.createTempDirectory("bazel-query-err"))
    val syntaxErr =
      "ERROR: Error while parsing 'kind(...)': syntax error at '- //excluded/...:all )'"
    val runner = new StubShellRunner((_, _) =>
      StubResponse(
        exitCode = 2,
        stderr = List("Loading: 0 packages loaded", syntaxErr),
      )
    )
    val env = BazelQuery.Env(workspace, runner, None)
    BazelQuery("kind('java_library', set(//...))", BazelQuery.OutputMode.Label)
      .run(env)
      .failed
      .map { ex =>
        assert(ex.getMessage.contains("exit code 2"), ex.getMessage)
        assert(ex.getMessage.contains(syntaxErr), ex.getMessage)
      }
  }

  test("query-run-succeeds-on-success-and-partial-keep-going-exit-code-3") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("bazel-query-partial"))
    val successRunner = new StubShellRunner((_, _) =>
      StubResponse(
        exitCode = ExitCodes.Success,
        stdout = List("//core:core"),
      )
    )
    val partialRunner = new StubShellRunner((_, _) =>
      StubResponse(
        exitCode = BazelQuery.PartialAnalysisFailureExitCode,
        stdout = List("//app:app"),
        stderr = List("WARNING: --keep_going specified, ignoring errors."),
      )
    )
    val cancelRunner =
      new StubShellRunner((_, _) => StubResponse(exitCode = ExitCodes.Cancel))
    for {
      okOut <- BazelQuery(
        "kind('java_library', set(//...))",
        BazelQuery.OutputMode.Label,
      ).run(BazelQuery.Env(workspace, successRunner, None))
      _ = assertEquals(okOut, s"//core:core${System.lineSeparator()}")
      partialOut <- BazelQuery(
        "kind('java_library', set(//...))",
        BazelQuery.OutputMode.Label,
      ).run(BazelQuery.Env(workspace, partialRunner, None))
      _ = assertEquals(partialOut, s"//app:app${System.lineSeparator()}")
      cancelEx <- BazelQuery(
        "kind('java_library', set(//...))",
        BazelQuery.OutputMode.Label,
      ).run(BazelQuery.Env(workspace, cancelRunner, None)).failed
      _ = assert(cancelEx.isInstanceOf[CancellationException])
    } yield ()
  }

  test("importer-aborts-on-failed-discovery-query-without-running-deps") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("bazel-importer-fail"))
    val runner = new StubShellRunner((cmdRun, _) =>
      if (cmdRun == "bazel-mbt-query")
        StubResponse(
          exitCode = 2,
          stderr = List("ERROR: query syntax error"),
        )
      else StubResponse(exitCode = 0)
    )
    val importer = new BazelMbtImporter(
      workspace,
      runner,
      () => UserConfiguration(),
      MetalsServerConfig.default,
    ) {}
    importer.extract(workspace).failed.map { ex =>
      assert(ex.getMessage.contains("exit code 2"), ex.getMessage)
      val ranDepsQuery =
        runner.recordedArgs.exists(_.exists(_.startsWith("deps(")))
      assert(!ranDepsQuery, s"unexpected deps query in ${runner.recordedArgs}")
      assert(!Files.exists(importer.outputPath(workspace).toNIO))
    }
  }

  test("importer-skips-deps-query-when-zero-targets-discovered") {
    val workspace =
      AbsolutePath(Files.createTempDirectory("bazel-importer-empty"))
    val runner = new StubShellRunner((_, _) => StubResponse(exitCode = 0))
    val importer = new BazelMbtImporter(
      workspace,
      runner,
      () => UserConfiguration(),
      MetalsServerConfig.default,
    ) {}
    importer.extract(workspace).map { _ =>
      val ranDepsQuery =
        runner.recordedArgs.exists(_.exists(_.startsWith("deps(")))
      assert(!ranDepsQuery, s"unexpected deps query in ${runner.recordedArgs}")
      assert(Files.exists(importer.outputPath(workspace).toNIO))
    }
  }

  test("mbt-import-surfaces-provider-failure-without-overwriting-build") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-import-fail"))
    val metalsDir = workspace.resolve(".metals")
    Files.createDirectories(metalsDir.toNIO)
    val existingMbtJson = """{"namespaces":{"existing":{}}}"""
    val mbtJsonPath = metalsDir.resolve("mbt.json").toNIO
    Files.writeString(mbtJsonPath, existingMbtJson)

    val shownMessages = ListBuffer.empty[MessageParams]
    val client = new NoopLanguageClient {
      override def showMessage(params: MessageParams): Unit =
        shownMessages.synchronized {
          shownMessages += params
        }
    }
    val tables = new Tables(workspace, Time.system)
    val mbtImport =
      new MbtImport(workspace, client, tables, () => UserConfiguration())

    val failingProvider = new MbtImportProvider {
      override def name: String = "bazel"
      override def projectRoot: AbsolutePath = workspace
      override def isBuildRelated(path: AbsolutePath): Boolean = false
      override def digest(ws: AbsolutePath): Option[String] = None
      override def extract(ws: AbsolutePath): Future[Unit] =
        Future.failed(new RuntimeException("bazel query failed"))
    }
    val cancelledProvider = new MbtImportProvider {
      override def name: String = "bazel"
      override def projectRoot: AbsolutePath = workspace
      override def isBuildRelated(path: AbsolutePath): Boolean = false
      override def digest(ws: AbsolutePath): Option[String] = None
      override def extract(ws: AbsolutePath): Future[Unit] =
        Future.failed(new CancellationException("cancelled"))
    }

    (for {
      failedStatus <- mbtImport.runUnconditionally(
        List(failingProvider),
        new AtomicBoolean(false),
      )
      _ = assertEquals(failedStatus, WorkspaceLoadedStatus.Failed(-1))
      _ = assertEquals(shownMessages.size, 1)
      _ = assertEquals(shownMessages.head, Messages.ImportProjectFailed)
      _ = assertEquals(Files.readString(mbtJsonPath), existingMbtJson)
      _ = shownMessages.clear()
      cancelledStatus <- mbtImport.runUnconditionally(
        List(cancelledProvider),
        new AtomicBoolean(false),
      )
      _ = assertEquals(cancelledStatus, WorkspaceLoadedStatus.Cancelled)
      _ = assert(shownMessages.isEmpty)
    } yield ()).andThen { case _ =>
      tables.cancel()
    }
  }
}
