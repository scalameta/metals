package tests.mcp

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.metals.mcp.EofAwareInputStream

import munit.FunSuite

class EofAwareInputStreamSuite extends FunSuite {

  test("delivers-bytes-then-notifies-once-on-eof") {
    val pipe = new PipedInputStream()
    val output = new PipedOutputStream(pipe)
    val calls = new AtomicInteger()
    val callbackThread = new AtomicReference[String]()
    val latch = new CountDownLatch(1)
    val stream = new EofAwareInputStream(
      pipe,
      () => {
        calls.incrementAndGet()
        callbackThread.set(Thread.currentThread().getName)
        latch.countDown()
      },
    )
    val message = "hello\n".getBytes(StandardCharsets.UTF_8)
    output.write(message)
    output.flush()

    val buffer = new Array[Byte](message.length)
    val read = stream.read(buffer)
    assertEquals(read, message.length)
    assertEquals(calls.get(), 0)

    output.close()
    assertEquals(stream.read(buffer), -1)
    assertEquals(stream.read(buffer), -1)
    assert(latch.await(5, TimeUnit.SECONDS))
    assertEquals(calls.get(), 1)
    assertEquals(callbackThread.get(), "metals-mcp-stdin-closed")
  }

  test("notifies-when-read-fails") {
    val pipe = new PipedInputStream()
    val output = new PipedOutputStream(pipe)
    val latch = new CountDownLatch(1)
    val stream = new EofAwareInputStream(pipe, () => latch.countDown())
    val writer = new Thread(() => output.write(1))
    writer.start()
    writer.join()

    assertEquals(stream.read(), 1)
    intercept[IOException] {
      stream.read()
    }
    assert(latch.await(5, TimeUnit.SECONDS))
  }
}
