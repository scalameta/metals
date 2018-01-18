package benchmarks

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import org.langmeta.jsonrpc._
import org.openjdk.jmh.annotations._

/**
 * benchmarks/jmh:run -i 10 -wi 10 -f1 -t1 benchmarks.HotBaseProtocolMessageParserBench
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class HotBaseProtocolMessageParserBench {

  @Benchmark
  def parse_1000_messages_in_increasing_size(): Unit = {
    val requests = 1.to(1000).map { i =>
      BaseProtocolMessage(
        Request("method", Some(Json.fromString(i.toString)), RequestId(i))
      )
    }
    val messages = BaseProtocolMessage.fromByteBuffers(
      Observable(requests: _*).map(MessageWriter.write),
      Logger("bench")
    )
    val s = TestScheduler()
    val f = messages.runAsyncGetLast(s)
    while (s.tickOne()) ()
    Await.result(f, Duration("10s"))
  }
}
