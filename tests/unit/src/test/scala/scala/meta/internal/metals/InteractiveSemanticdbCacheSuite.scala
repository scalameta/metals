package scala.meta.internal.metals

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import munit.FunSuite
import munit.Location

class InteractiveSemanticdbCacheSuite extends FunSuite {
  private val compilerA = new Object()
  private val compilerB = new Object()
  private val fallbackCompiler = new Object()
  private val laneA = InteractiveSemanticdbCompilationLane(compilerA)
  private val laneB = InteractiveSemanticdbCompilationLane(compilerB)
  private val timeout = 5.seconds
  private val mustRemainBlockedFor = 250.millis

  private val executionContext = FunFixture[ExecutionContextExecutorService](
    setup = _ =>
      ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4)),
    // MUnit's default execution context is parasitic, so teardown can run on
    // the worker that completed the test Future. A graceful shutdown avoids
    // interrupting that thread and leaking its interrupt status to the next test.
    teardown = executor => executor.shutdown(),
  )

  private def await(
      latch: CountDownLatch,
      description: String,
      duration: FiniteDuration = timeout,
  )(implicit location: Location): Unit = {
    val completed = latch.await(duration.length, duration.unit)
    assert(
      completed,
      s"timed out waiting for $description",
    )
  }

  private def withRelease[A](release: CountDownLatch)(body: => A): A =
    try body
    finally release.countDown()

  executionContext.test("same-source misses compile once") { implicit ec =>
    val cache = new InteractiveSemanticdbCache[String, String]()
    val compileEntered = new CountDownLatch(1)
    val releaseCompile = new CountDownLatch(1)
    val secondLookupStarted = new CountDownLatch(1)
    val compileCount = new AtomicInteger()

    def lookup() = Future {
      compute(cache, "A.scala", laneA, _ == "current") { _ =>
        compileCount.incrementAndGet()
        compileEntered.countDown()
        await(releaseCompile, "release of same-source compilation")
        "current"
      }
    }

    val first = lookup()
    val second = withRelease(releaseCompile) {
      await(compileEntered, "first same-source compilation")
      val lookup = Future {
        secondLookupStarted.countDown()
        compute(cache, "A.scala", laneA, _ == "current") { _ =>
          compileCount.incrementAndGet()
          await(releaseCompile, "release of duplicate compilation")
          "current"
        }
      }
      await(secondLookupStarted, "second same-source lookup")
      assert(!lookup.isCompleted, "second lookup completed before compilation")
      lookup
    }

    for {
      firstResult <- first
      secondResult <- second
    } yield {
      assertEquals(firstResult, "current")
      assertEquals(secondResult, "current")
      assertEquals(compileCount.get(), 1)
    }
  }

  executionContext.test("cache hit progresses while another source compiles") {
    implicit ec =>
      val cache = new InteractiveSemanticdbCache[String, String]()
      compute(cache, "B.scala", laneB, _ => false)(_ => "cached")
      val compileEntered = new CountDownLatch(1)
      val releaseCompile = new CountDownLatch(1)
      val hitCompleted = new CountDownLatch(1)

      val miss = Future {
        compute(cache, "A.scala", laneA, _ => false) { _ =>
          compileEntered.countDown()
          await(releaseCompile, "release of cache-miss compilation")
          "compiled"
        }
      }
      val hit = withRelease(releaseCompile) {
        await(compileEntered, "cache-miss compilation")
        val lookup = Future {
          try
            compute(cache, "B.scala", laneB, _ == "cached") { _ =>
              fail("a current cache entry must not compile")
            }
          finally hitCompleted.countDown()
        }
        await(
          hitCompleted,
          "cache hit while another source compiles",
          timeout,
        )
        lookup
      }

      for {
        hitResult <- hit
        missResult <- miss
      } yield {
        assertEquals(hitResult, "cached")
        assertEquals(missResult, "compiled")
      }
  }

  checkSerialized(
    "different-source misses using one compiler serialize access",
    laneA,
    laneA,
  )

  executionContext.test("different-target misses compile concurrently") {
    implicit ec =>
      val cache = new InteractiveSemanticdbCache[String, String]()
      val workersReady = new CountDownLatch(2)
      val startLookups = new CountDownLatch(1)
      val compileEntered = new CountDownLatch(2)
      val releaseCompile = new CountDownLatch(1)

      def lookup(path: String, lane: InteractiveSemanticdbCompilationLane) =
        Future {
          workersReady.countDown()
          await(startLookups, "simultaneous lookup start")
          compute(cache, path, lane, _ => false) { _ =>
            compileEntered.countDown()
            await(releaseCompile, "release of cross-target compilations")
            path
          }
        }

      val first = lookup("A.scala", laneA)
      val second = lookup("B.scala", laneB)
      withRelease(releaseCompile) {
        await(workersReady, "both cross-target workers")
        startLookups.countDown()
        await(compileEntered, "both cross-target compilations")
      }

      for {
        firstResult <- first
        secondResult <- second
      } yield {
        assertEquals(firstResult, "A.scala")
        assertEquals(secondResult, "B.scala")
      }
  }

  executionContext.test(
    "hash-colliding keys on different compilers compile concurrently"
  ) { implicit ec =>
    val cache =
      new InteractiveSemanticdbCache[
        InteractiveSemanticdbCollisionKey,
        String,
      ]()
    val compileEntered = new CountDownLatch(2)
    val releaseCompile = new CountDownLatch(1)

    def lookup(
        key: InteractiveSemanticdbCollisionKey,
        lane: InteractiveSemanticdbCompilationLane,
    ) = Future {
      val compilation = new InteractiveSemanticdbCompilation(
        lane,
        InteractiveSemanticdbCompilationContext.Scala(lane),
        () => null,
      )
      cache.compute(key, () => Success(compilation), _ => false) { (_, _) =>
        compileEntered.countDown()
        await(releaseCompile, "release of hash-colliding compilations")
        key.value
      }
    }

    val first = lookup(InteractiveSemanticdbCollisionKey("A.scala"), laneA)
    val second = lookup(InteractiveSemanticdbCollisionKey("B.scala"), laneB)
    withRelease(releaseCompile) {
      await(compileEntered, "both hash-colliding compilations")
    }

    for {
      firstResult <- first
      secondResult <- second
    } yield {
      assertEquals(firstResult, "A.scala")
      assertEquals(secondResult, "B.scala")
    }
  }

  checkSerialized(
    "nominal targets sharing the fallback compiler serialize access",
    InteractiveSemanticdbCompilationLane(fallbackCompiler),
    InteractiveSemanticdbCompilationLane(fallbackCompiler),
  )

  test("compiler change invalidates unchanged cached text") {
    val cache = new InteractiveSemanticdbCache[String, String]()
    val compileCount = new AtomicInteger()

    val fromCompilerA = compute(cache, "A.scala", laneA, _ => false) { _ =>
      compileCount.incrementAndGet()
      "compiler-a"
    }
    val fromCompilerB = compute(cache, "A.scala", laneB, _ => true) { _ =>
      compileCount.incrementAndGet()
      "compiler-b"
    }

    assertEquals(fromCompilerA, "compiler-a")
    assertEquals(fromCompilerB, "compiler-b")
    assertEquals(compileCount.get(), 2)
  }

  test("Java build-target change invalidates unchanged cached text") {
    val cache = new InteractiveSemanticdbCache[String, String]()

    def compilation(target: String) =
      new InteractiveSemanticdbCompilation(
        laneA,
        InteractiveSemanticdbCompilationContext.Java(Some(target)),
        () => null,
      )

    val fromTargetA = cache.compute(
      "A.java",
      () => Success(compilation("target:A")),
      _ => false,
    )((_, _) => "target-a")
    val fromTargetB = cache.compute(
      "A.java",
      () => Success(compilation("target:B")),
      _ => true,
    )((_, _) => "target-b")

    assertEquals(fromTargetA, "target-a")
    assertEquals(fromTargetB, "target-b")
  }

  executionContext.test("clear waits for active compilation") { implicit ec =>
    val cache = new InteractiveSemanticdbCache[String, String]()
    val firstCompileEntered = new CountDownLatch(1)
    val clearStarted = new CountDownLatch(1)
    val secondLookupStarted = new CountDownLatch(1)
    val secondCompileEntered = new CountDownLatch(1)
    val releaseCompile = new CountDownLatch(1)
    val activeCompilations = new AtomicInteger()
    val maximumActiveCompilations = new AtomicInteger()

    def compile(path: String, entered: CountDownLatch): String = {
      val active = activeCompilations.incrementAndGet()
      maximumActiveCompilations.accumulateAndGet(active, Math.max)
      entered.countDown()
      try {
        await(releaseCompile, s"release of $path compilation")
        path
      } finally activeCompilations.decrementAndGet()
    }

    val first = Future {
      compute(cache, "A.scala", laneA, _ => false) { path =>
        compile(path, firstCompileEntered)
      }
    }
    await(firstCompileEntered, "compilation active during clear")
    val clear = Future {
      clearStarted.countDown()
      cache.clear()
    }
    await(clearStarted, "clear invocation")
    assert(!clear.isCompleted, "clear completed during compilation")
    val second = Future {
      secondLookupStarted.countDown()
      compute(cache, "B.scala", laneA, _ => false) { path =>
        compile(path, secondCompileEntered)
      }
    }

    withRelease(releaseCompile) {
      await(secondLookupStarted, "post-clear lookup")
      val enteredWhileFirstWasActive = secondCompileEntered.await(
        mustRemainBlockedFor.length,
        mustRemainBlockedFor.unit,
      )
      assert(
        !enteredWhileFirstWasActive,
        "clear allowed concurrent compilation in one compiler lane",
      )
    }

    for {
      firstResult <- first
      _ <- clear
      secondResult <- second
    } yield {
      assertEquals(firstResult, "A.scala")
      assertEquals(secondResult, "B.scala")
      assertEquals(maximumActiveCompilations.get(), 1)
    }
  }

  executionContext.test("remove waits for compilation and removes its result") {
    implicit ec =>
      val cache = new InteractiveSemanticdbCache[String, String]()
      val compileEntered = new CountDownLatch(1)
      val releaseCompile = new CountDownLatch(1)
      val removeStarted = new CountDownLatch(1)

      val lookup = Future {
        compute(cache, "A.scala", laneA, _ => false) { _ =>
          compileEntered.countDown()
          await(releaseCompile, "release of compilation before removal")
          "compiled"
        }
      }
      await(compileEntered, "compilation before removal")
      val remove = Future {
        removeStarted.countDown()
        cache.remove("A.scala")
      }

      withRelease(releaseCompile) {
        await(removeStarted, "removal during compilation")
        assert(!remove.isCompleted, "remove completed during compilation")
      }

      for {
        lookupResult <- lookup
        _ <- remove
      } yield {
        assertEquals(lookupResult, "compiled")
        val afterRemove = compute(cache, "A.scala", laneA, _ => true) { _ =>
          "recompiled"
        }
        assertEquals(afterRemove, "recompiled")
      }
  }

  executionContext.test("clear waits for compiler selection before clearing") {
    implicit ec =>
      val cache = new InteractiveSemanticdbCache[String, String]()
      val prepareEntered = new CountDownLatch(1)
      val releasePrepare = new CountDownLatch(1)
      val clearStarted = new CountDownLatch(1)
      val compilation = new InteractiveSemanticdbCompilation(
        laneA,
        InteractiveSemanticdbCompilationContext.Scala(laneA),
        () => null,
      )

      val lookup = Future {
        cache.compute(
          "A.scala",
          () => {
            prepareEntered.countDown()
            await(releasePrepare, "release of compiler selection")
            Success(compilation)
          },
          _ => false,
        )((_, _) => "before-clear")
      }
      await(prepareEntered, "compiler selection during clear")
      val clear = Future {
        clearStarted.countDown()
        cache.clear()
      }

      withRelease(releasePrepare) {
        await(clearStarted, "clear during compiler selection")
        assert(!clear.isCompleted, "clear completed during compiler selection")
      }

      for {
        lookupResult <- lookup
        _ <- clear
      } yield {
        assertEquals(lookupResult, "before-clear")
        val afterClear = compute(cache, "A.scala", laneA, _ => true) { _ =>
          "after-clear"
        }
        assertEquals(afterClear, "after-clear")
      }
  }

  test("failed compiler selection is contained") {
    val cache = new InteractiveSemanticdbCache[String, String]()
    val failure = new IllegalStateException("compiler selection failed")

    val result = cache.compute(
      "A.scala",
      () => Failure(failure),
      _ => true,
    )((_, _) => fail("failed compiler selection must not compile"))

    assertEquals(result, null)
  }

  executionContext.test("failed compilation releases its lane") { implicit ec =>
    val cache = new InteractiveSemanticdbCache[String, String]()
    val failure = new IllegalStateException("compile failed")

    val failed = Future {
      compute(cache, "A.scala", laneA, _ => false)(_ => throw failure)
    }

    failed.failed.map { obtained =>
      assertEquals(obtained, failure)
      val recovered = compute(cache, "B.scala", laneA, _ => false)(_ => "ok")
      assertEquals(recovered, "ok")
    }
  }

  executionContext.test("null compilation result is not cached") { _ =>
    val cache = new InteractiveSemanticdbCache[String, String]()
    val compileCount = new AtomicInteger()

    val missing = compute(cache, "A.scala", laneA, _ => false) { _ =>
      compileCount.incrementAndGet()
      null
    }
    val recovered = compute(cache, "A.scala", laneA, _ => false) { _ =>
      compileCount.incrementAndGet()
      "current"
    }

    assertEquals(missing, null)
    assertEquals(recovered, "current")
    assertEquals(compileCount.get(), 2)
  }

  private def compute(
      cache: InteractiveSemanticdbCache[String, String],
      key: String,
      lane: InteractiveSemanticdbCompilationLane,
      isCurrent: String => Boolean,
  )(compile: String => String): String = {
    val compilation = new InteractiveSemanticdbCompilation(
      lane,
      InteractiveSemanticdbCompilationContext.Scala(lane),
      () => null,
    )
    cache.compute(key, () => Success(compilation), isCurrent) { (path, _) =>
      compile(path)
    }
  }

  private def checkSerialized(
      name: String,
      firstLane: InteractiveSemanticdbCompilationLane,
      secondLane: InteractiveSemanticdbCompilationLane,
  )(implicit location: Location): Unit =
    executionContext.test(name) { implicit ec =>
      val cache = new InteractiveSemanticdbCache[String, String]()
      val firstCompileEntered = new CountDownLatch(1)
      val secondLookupReachedCache = new CountDownLatch(1)
      val secondCompileEntered = new CountDownLatch(1)
      val releaseCompile = new CountDownLatch(1)
      val activeCompilations = new AtomicInteger()
      val maximumActiveCompilations = new AtomicInteger()

      def compile(path: String, entered: CountDownLatch): String = {
        val active = activeCompilations.incrementAndGet()
        maximumActiveCompilations.accumulateAndGet(active, Math.max)
        entered.countDown()
        try {
          await(releaseCompile, s"release of $path compilation")
          path
        } finally activeCompilations.decrementAndGet()
      }

      compute(cache, "B.scala", secondLane, _ => false)(_ => "stale")

      val first = Future {
        compute(cache, "A.scala", firstLane, _ => false) { path =>
          compile(path, firstCompileEntered)
        }
      }
      val second = withRelease(releaseCompile) {
        await(firstCompileEntered, "first serialized compilation")
        val lookup = Future {
          compute(
            cache,
            "B.scala",
            secondLane,
            _ => {
              secondLookupReachedCache.countDown()
              false
            },
          ) { path =>
            compile(path, secondCompileEntered)
          }
        }
        await(secondLookupReachedCache, "second serialized cache lookup")
        val enteredWhileFirstWasActive = secondCompileEntered.await(
          mustRemainBlockedFor.length,
          mustRemainBlockedFor.unit,
        )
        assert(
          !enteredWhileFirstWasActive,
          "second compilation entered an already active lane",
        )
        assertEquals(maximumActiveCompilations.get(), 1)
        lookup
      }

      for {
        firstResult <- first
        secondResult <- second
      } yield {
        assertEquals(firstResult, "A.scala")
        assertEquals(secondResult, "B.scala")
        assertEquals(maximumActiveCompilations.get(), 1)
      }
    }
}

private final case class InteractiveSemanticdbCollisionKey(value: String) {
  override def hashCode(): Int = 0
}
