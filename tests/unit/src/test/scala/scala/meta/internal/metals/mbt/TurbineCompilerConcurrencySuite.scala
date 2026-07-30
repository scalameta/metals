package scala.meta.internal.metals.mbt

import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.parallel.mutable.ParArray
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Sleeper
import scala.meta.pc.ProgressBars

import com.google.turbine.diag.SourceFile

class TurbineCompilerConcurrencySuite extends munit.FunSuite {
  private val executor = Executors.newFixedThreadPool(4)
  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutorService(executor)
  private implicit val rc: ReportContext = EmptyReportContext

  override def afterAll(): Unit = executor.shutdownNow()

  test("compile-now-supersedes-running-scheduled-compile") {
    val sleeper = new ControllableSleeper()
    val firstCompileStarted = Promise[Unit]()
    val releaseFirstCompile = Promise[Unit]()
    val compileCount = new AtomicInteger()
    val indexingCount = new AtomicInteger()
    val compiler = newCompiler(
      () => {
        if (compileCount.incrementAndGet() == 1) {
          firstCompileStarted.trySuccess(())
          Await.result(releaseFirstCompile.future, 10.seconds)
          sources("Stale")
        } else sources("Current")
      },
      sleeper,
      indexingCount,
    )

    val scheduled = compiler.scheduleCompile()
    sleeper.release()
    for {
      _ <- firstCompileStarted.future
      current = compiler.compileNow()
      _ = releaseFirstCompile.trySuccess(())
      result <- current
      cancellation <- scheduled.failed
    } yield {
      assert(cancellation.isInstanceOf[CancellationException])
      assertEquals(compileCount.get(), 2)
      assertEquals(indexingCount.get(), 1)
      assertContains(result, "Current")
      assertNotContains(result, "Stale")
    }
  }

  test("scheduled-compile-publishes-current-generation") {
    val sleeper = new ControllableSleeper()
    val indexingCount = new AtomicInteger()
    val compiler = newCompiler(
      () => sources("Scheduled"),
      sleeper,
      indexingCount,
    )

    val scheduled = compiler.scheduleCompile()
    sleeper.release()
    scheduled.map { result =>
      assertEquals(indexingCount.get(), 1)
      assertContains(result, "Scheduled")
    }
  }

  private def newCompiler(
      allCompilationUnits: () => ParArray[String],
      sleeper: Sleeper,
      indexingCount: AtomicInteger,
  ): TurbineCompiler[String] =
    new TurbineCompiler[String](
      allCompilationUnits = allCompilationUnits,
      parseUnit = name =>
        Seq(
          new SourceFile(
            s"$name.java",
            s"package test; public class $name {}",
          )
        ),
      classpath = () => Nil,
      progressBars = ProgressBars.EMPTY,
      turbineRecompileDelay = () => TurbineRecompileDelayConfig(1.millis),
      listProtoJavaOutlinesForPackage = _ => Iterator.empty,
      sleeper = sleeper,
      onIndexingDone = () => indexingCount.incrementAndGet(),
      onNewProjectClasspath = _ => (),
    )

  private def sources(name: String): ParArray[String] =
    ParArray.fromSpecific(List(name))

  private def assertContains(
      result: TurbineCompileResult,
      name: String,
  ): Unit =
    assert(
      binaryNames(result).exists(_.endsWith(name)),
      binaryNames(result).mkString(", "),
    )

  private def assertNotContains(
      result: TurbineCompileResult,
      name: String,
  ): Unit =
    assert(
      !binaryNames(result).exists(_.endsWith(name)),
      binaryNames(result).mkString(", "),
    )

  private def binaryNames(result: TurbineCompileResult): Set[String] =
    result.lowered.symbols().asScala.map(_.binaryName()).toSet

  private class ControllableSleeper extends Sleeper {
    private val sleeping = Promise[Unit]()

    override def sleep(duration: FiniteDuration): Future[Unit] =
      sleeping.future

    def release(): Unit = sleeping.trySuccess(())
  }
}
