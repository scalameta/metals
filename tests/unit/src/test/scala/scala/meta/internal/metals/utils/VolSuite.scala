package scala.meta.internal.metals.utils
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite

class VolSuite extends FunSuite {

  import VolSuite._

  test("flatmap-should-evaluate-repetitions-just-once") {
    val x = new ReferenceCountingVol()
    val y = for {
      first <- x
      second <- x
      third <- x
    } yield (first, second, third)
    assertEquals(y.snapshot(), (1, 1, 1))
  }

  test("separate-flatmap-should-evaluate-from-scratch") {
    val x = new ReferenceCountingVol()
    val y = for {
      first <- x
      second <- x
    } yield (first, second)
    assertEquals(y.snapshot(), (1, 1))

    val z = for {
      first <- x
      second <- x
    } yield (first, second)
    assertEquals(z.snapshot(), (2, 2))
  }

  test("separate-flatmap-should-evaluate-from-scratch-xxx") {
    val y = for {
      first <- Stopwatch
      second <- Stopwatch
    } yield (first, second)

    val z = for {
      first <- Stopwatch
      second <- Stopwatch
    } yield (first, second)
    assertNotEquals(y.snapshot(), z.snapshot())
  }

  test("evaluation-in-different-threads-should-allow-diferent-values") {
    var snapshot1: Long = -1L
    var snapshot2: Long = -1L
    Thread.ofVirtual().start { () => snapshot1 = Stopwatch.snapshot() }
    Thread.ofVirtual().start { () => snapshot2 = Stopwatch.snapshot() }
    Thread.sleep(1000)

    assertNotEquals(snapshot1 - snapshot2, 0L)
  }

  // Exposes: ThreadLocal cache is never cleared, so a top-level Vol is
  // frozen forever on a thread after the first snapshot() — contradicting
  // "the value may change more than once".
  test("atomic-ref-should-see-updates-across-separate-snapshots") {
    val ref = new AtomicReference(1)
    val vol = Vol.AtomicRef(ref)
    assertEquals(vol.snapshot(), 1)
    ref.set(2)
    assertEquals(vol.snapshot(), 2)
  }

  // Exposes: FlatMapped.eval calls result.eval() instead of result.snapshot(),
  // so a leaf Vol returned from flatMap is not entered into the snapshot cache
  // and is re-evaluated when read again in the same comprehension.
  test("flatmap-to-leaf-vol-should-be-cached-in-enclosing-comprehension") {
    val b = new ReferenceCountingVol()
    val a = Vol.Function(() => 0)
    val result = for {
      x <- a.flatMap(_ => b)
      y <- b
    } yield (x, y)
    assertEquals(result.snapshot(), (1, 1))
  }

  // Exposes: Function/AtomicRef are case classes, so structurally equal Vols
  // share a single cache entry even when they are distinct instances.
  test("distinct-function-vols-sharing-lambda-should-not-share-cache") {
    val counter = new AtomicInteger(0)
    val f = () => counter.incrementAndGet()
    val v1 = Vol.Function(f)
    val v2 = Vol.Function(f)
    assertEquals(v1.snapshot(), 1)
    assertEquals(v2.snapshot(), 2)
  }

}

object VolSuite {
  private class ReferenceCountingVol extends Vol[Int] {
    val value = new AtomicInteger(0)
    override def eval(): Int = value.incrementAndGet()

  }

  private val Stopwatch = Vol.Function[Long](() => System.nanoTime())
}
