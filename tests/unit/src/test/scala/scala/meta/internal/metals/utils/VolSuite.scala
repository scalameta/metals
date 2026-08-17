package scala.meta.internal.metals.utils
import java.util.concurrent.atomic.AtomicInteger
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

  test("evaluation-in-the-same-thread-should-yield-the-same-value") {
    val snapshot1 = Stopwatch.snapshot()
    Thread.sleep(100)
    val snapshot2 = Stopwatch.snapshot()
    assertEquals(snapshot1, snapshot2)
  }

  test("evaluation-in-different-threads-should-allow-diferent-values") {
    var snapshot1: Long = -1L
    var snapshot2: Long = -1L
    Thread.ofVirtual().start { () => snapshot1 = Stopwatch.snapshot() }
    Thread.ofVirtual().start { () => snapshot2 = Stopwatch.snapshot() }
    Thread.sleep(1000)

    assertNotEquals(snapshot1 - snapshot2, 0L)
  }

}

object VolSuite {
  private class ReferenceCountingVol extends Vol[Int] {
    val value = new AtomicInteger(0)
    override def eval(): Int = value.incrementAndGet()

  }

  private val Stopwatch = Vol.Function[Long](() => System.nanoTime())
}
