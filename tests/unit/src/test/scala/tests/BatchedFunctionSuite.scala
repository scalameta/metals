package tests

import scala.concurrent.Future
import scala.meta.internal.metals.BatchedFunction
import scala.concurrent.ExecutionContext.Implicits.global

object BatchedFunctionSuite extends BaseSuite {
  testAsync("batch") {
    val lock = new Object
    val mkString = new BatchedFunction[String, String]({ numbers =>
      Future {
        lock.synchronized {
          numbers.mkString
        }
      }
    })
    val results = lock.synchronized {
      List(
        // First request instantly acquires lock and runs solo
        mkString(List("a")),
        // Following requests will be buffered and run together
        mkString(List("b")),
        mkString(List("c"))
      )
    }

    Future.sequence(results).map { results =>
      assertNoDiff(
        results.mkString("\n"),
        """
          |a
          |bc
          |bc
        """.stripMargin
      )
    }
  }

}
