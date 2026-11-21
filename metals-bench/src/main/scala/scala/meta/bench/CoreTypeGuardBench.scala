package scala.meta.bench

import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

import scala.util.Using

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.PackageIndex
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.MetalsGlobal
import scala.meta.internal.pc.ScalaCompilerAccess
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.pc.HoverSignature
import scala.meta.pc.SymbolSearch
import scala.meta.pc.reports.EmptyReportContext

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams

/**
 * Measures the real request boundary, including the queue and recovery guard.
 * See core-type-guard.md for the separate-build comparison protocol.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
class CoreTypeGuardBench extends PcBenchmark {
  private var pc: ScalaPresentationCompiler = _
  private var global: MetalsGlobal = _
  private var warm: CompilerOffsetParams = _
  private var edits: Array[(CompilerVirtualFileParams, CompilerOffsetParams)] =
    _
  private var nextEdit = 0
  private var operations = 0L
  private var editOperations = 0L
  private var initialRun = 0
  private var editing = false
  private val expected = "def pick(value: String): String"

  // Use this checkout's compiler and library, without downloading other compiler
  // versions or the larger corpus used by the general PC benchmarks.
  override def classpath: List[java.nio.file.Path] =
    PackageIndex.scalaLibrary.toList
  override def newSearch(): SymbolSearch = EmptySymbolSearch

  // PcBenchmark already annotates setup; JMH calls this override through it.
  override def setup(): Unit = beforeAll()

  override def beforeAll(): Unit = {
    require(BuildInfo.scalaCompilerVersion.startsWith("2.13."))
    pc = presentationCompiler(BuildInfo.scalaCompilerVersion)
      .asInstanceOf[ScalaPresentationCompiler]
    val source =
      "object Sample { def pick(value: String): String = value; def pick(value: Any): Any = value; val result = pick(\"x\"); val cast = result.asInstanceOf[String] }"
    val offset = source.indexOf("pick(\"x\")") + 2
    warm = CompilerOffsetParams(
      Paths.get("GuardWarm.scala").toUri,
      source.replace("Sample", "Warmed"),
      offset,
    )
    edits = Array(0, 1).map { index =>
      val text = source.replace("Sample", "Edited") + s" // edit $index"
      val uri = Paths.get("GuardEdit.scala").toUri
      (
        CompilerVirtualFileParams(uri, text, shouldReturnDiagnostics = true),
        CompilerOffsetParams(uri, text, offset),
      )
    }
    println(
      s"guard-bench-artifacts scala=${BuildInfo.scalaCompilerVersion} access=${classHash(classOf[ScalaCompilerAccess])} benchmark=${classHash(classOf[CoreTypeGuardBench])} compiler=${classHash(classOf[scala.tools.nsc.Global])} library=${classHash(classOf[Option[_]])}"
    )
    checkedHover(warm)
    implicit val query: PcQueryContext =
      PcQueryContext(None, () => "")(new EmptyReportContext())
    global = pc.compilerAccess
      .withSharedCompiler(Option.empty[MetalsGlobal])(w => Some(w.compiler()))
      .get
  }

  @Setup(Level.Iteration)
  def startIteration(
      params: BenchmarkParams,
      iteration: IterationParams,
  ): Unit = {
    editing = params.getBenchmark.endsWith(".editBatch")
    val expectedOps = if (editing) CoreTypeGuardBench.EditsPerBatch else 1
    require(params.getThreads == 1 && iteration.getBatchSize == 1)
    require(params.getOpsPerInvocation == expectedOps)
    operations = 0L
    editOperations = 0L
    initialRun = global.currentRunId
  }

  @TearDown(Level.Iteration)
  def checkIteration(): Unit = {
    val runs = global.currentRunId - initialRun
    require(runs.toLong == editOperations)
    require(!editing || operations == CoreTypeGuardBench.EditsPerBatch)
    println(s"guard-bench-iteration operations=$operations runs=$runs")
  }

  private def classHash(cls: Class[_]): String = {
    val bytes =
      Using.resource(cls.getResourceAsStream(cls.getSimpleName + ".class"))(
        _.readAllBytes()
      )
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
  }

  private def checkedHover(params: CompilerOffsetParams): HoverSignature = {
    val result = pc.hover(params).get(30, TimeUnit.SECONDS)
    require(result.isPresent && result.get().signature().orElse("") == expected)
    result.get()
  }

  @Benchmark
  def warmHover(): HoverSignature = {
    operations += 1
    checkedHover(warm)
  }

  // Fixed work also fixes the compiler-run history in every fork. Time-based
  // edit iterations would let faster forks reach different resident states.
  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OperationsPerInvocation(CoreTypeGuardBench.EditsPerBatch)
  @Warmup(iterations = 20, batchSize = 1)
  @Measurement(iterations = 10, batchSize = 1)
  def editBatch(): HoverSignature = {
    var result: HoverSignature = null
    var index = 0
    while (index < CoreTypeGuardBench.EditsPerBatch) {
      val (change, hover) = edits(nextEdit)
      nextEdit = 1 - nextEdit
      require(pc.didChange(change).get(30, TimeUnit.SECONDS).isEmpty)
      operations += 1
      editOperations += 1
      result = checkedHover(hover)
      index += 1
    }
    result
  }
}

object CoreTypeGuardBench {
  final val EditsPerBatch = 1000
}
