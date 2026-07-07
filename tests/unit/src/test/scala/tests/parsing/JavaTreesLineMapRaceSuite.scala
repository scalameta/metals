package tests.parsing

import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import scala.meta.internal.metals.Buffers
import scala.meta.internal.parsing.JavaRange
import scala.meta.internal.parsing.JavaTrees
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Position
import tests.BaseSuite

/**
 * Regression test for the corrupt-edit flakiness in
 * `AddMissingReturnStatementLspSuite`.
 *
 * javac's `LineMapImpl.getLineNumber` memoizes in unsynchronized fields. Java
 * code-action providers query the same cached tree's line map concurrently, so
 * the memo races and returns wrong lines — ranges inconsistent with offsets.
 *
 * A single `findEnclosingJavaMethod` query already converts six distinct
 * offsets (the endpoints of `range`, `nameRange` and `bodyRange`) through the
 * memo, so threads querying even the same cursor drift out of phase and
 * corrupt each other — just like code-action providers resolving one cursor.
 * The concurrent test hammers the shared tree from a start barrier for a
 * fixed time window; the single-threaded control must always pass.
 */
class JavaTreesLineMapRaceSuite extends BaseSuite {
  private val text =
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |  }
       |}
       |""".stripMargin

  /** Cursor inside `answer`. */
  private val position = new Position(3, 16)

  // Enough for the failure message to show the corruption pattern; hitting it
  // also stops a failing thread early instead of hammering out the window.
  private val maxErrorsShown = 4

  test("single-threaded-reads-of-line-map") {
    val (javaTrees, path) = newCachedTrees()
    val errors =
      (0 until 10000).toList.flatMap { i =>
        validateEnclosingJavaMethodRange(javaTrees, path, s"iter=$i")
      }
    assert(errors.isEmpty, clue = errors.take(5).mkString("\n"))
  }

  test("multi-threaded-reads-of-line-map") {
    val (javaTrees, path) = newCachedTrees()
    val threads = 4
    val pool = Executors.newFixedThreadPool(threads)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    val highLoadDuration = 2.seconds
    val highLoadStartBarrier = new CyclicBarrier(threads)
    try {
      val workers = (0 until threads).toList.map { thread =>
        Future {
          // Release all threads together, then query continuously for a fixed
          // window so their line-map lookups overlap for the whole duration.
          highLoadStartBarrier.await()
          val deadline = System.nanoTime() + highLoadDuration.toNanos
          Iterator
            .from(0)
            .takeWhile(_ => System.nanoTime() < deadline)
            .flatMap { i =>
              validateEnclosingJavaMethodRange(
                javaTrees,
                path,
                s"thread=$thread iter=$i",
              )
            }
            .take(maxErrorsShown)
            .toList
        }
      }
      val errors =
        Await.result(Future.sequence(workers), Duration("120s")).flatten
      assert(
        errors.isEmpty,
        clue =
          s"${errors.length} inconsistent ranges, e.g.:\n${errors.take(maxErrorsShown).mkString("\n")}",
      )
    } finally {
      pool.shutdown()
    }
  }

  private def newCachedTrees(): (JavaTrees, AbsolutePath) = {
    val buffers = Buffers()
    val javaTrees = new JavaTrees(buffers)
    val path = AbsolutePath(Files.createTempFile("Example", ".java"))
    buffers.put(path, text)
    // Populate the cache so all reads share one tree (and one LineMapImpl).
    javaTrees.didChange(path)
    (javaTrees, path)
  }

  private def validateEnclosingJavaMethodRange(
      javaTrees: JavaTrees,
      path: AbsolutePath,
      label: String,
  ): List[String] =
    javaTrees.findEnclosingJavaMethod(path, position) match {
      case None =>
        List(s"$label: expected to find enclosing method at $position")
      case Some(method) =>
        List(
          s"$label range" -> method.range,
          s"$label nameRange" -> method.nameRange,
          s"$label bodyRange" -> method.bodyRange,
        ).flatMap { case (rangeLabel, range) =>
          positionErrors(rangeLabel, range)
        }
    }

  /** LSP positions of a range must agree with its own byte offsets. */
  private def positionErrors(
      label: String,
      range: JavaRange,
  ): List[String] =
    List(
      positionError(
        s"$label start",
        range.startOffset,
        range.range.getStart(),
      ),
      positionError(
        s"$label end",
        range.endOffset,
        range.range.getEnd(),
      ),
    ).flatten

  private def positionError(
      label: String,
      offset: Int,
      actual: Position,
  ): Option[String] = {
    val expected = offsetToPosition(offset)
    Option.when(expected != actual)(
      s"$label: offset $offset is $expected but range was $actual"
    )
  }

  private def offsetToPosition(offset: Int): Position = {
    val prefix = text.substring(0, offset)
    val line = prefix.count(_ == '\n')
    new Position(line, offset - (prefix.lastIndexOf('\n') + 1))
  }
}
