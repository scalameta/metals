package tests.bazelnative

import scala.meta.internal.builds.bazelnative.BazelNativeTargetData
import scala.meta.internal.builds.bazelnative.BspTargetInfo

import munit.FunSuite

class BazelNativeTargetDataSuite extends FunSuite {

  test("update populates store") {
    val data = new BazelNativeTargetData()
    val targets = Map(
      "//src:lib" -> BspTargetInfo(id = "//src:lib", kind = "scala_library"),
      "//src:bin" -> BspTargetInfo(id = "//src:bin", kind = "scala_binary"),
    )
    data.update(targets)

    assertEquals(data.get("//src:lib"), Some(targets("//src:lib")))
    assertEquals(data.get("//src:bin"), Some(targets("//src:bin")))
  }

  test("get returns None for unknown label") {
    val data = new BazelNativeTargetData()
    assertEquals(data.get("//unknown"), None)
  }

  test("clear empties the store") {
    val data = new BazelNativeTargetData()
    data.update(
      Map(
        "//src:lib" -> BspTargetInfo(id = "//src:lib", kind = "scala_library")
      )
    )
    data.clear()
    assert(data.isEmpty)
    assertEquals(data.get("//src:lib"), None)
  }

  test("successive updates overwrite") {
    val data = new BazelNativeTargetData()
    data.update(Map("//src:lib" -> BspTargetInfo(id = "//src:lib", kind = "A")))
    data.update(Map("//src:lib" -> BspTargetInfo(id = "//src:lib", kind = "B")))

    assertEquals(data.get("//src:lib").map(_.kind), Some("B"))
  }

  test("allTargets returns all entries") {
    val data = new BazelNativeTargetData()
    val targets = Map(
      "//a:lib" -> BspTargetInfo(id = "//a:lib", kind = "scala_library"),
      "//b:lib" -> BspTargetInfo(id = "//b:lib", kind = "scala_library"),
      "//c:lib" -> BspTargetInfo(id = "//c:lib", kind = "scala_library"),
    )
    data.update(targets)

    assertEquals(data.allTargets.size, 3)
    assertEquals(data.allTargets, targets)
  }

  test("concurrent access does not throw") {
    val data = new BazelNativeTargetData()
    val failures = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
    val threads = (1 to 10).map { i =>
      new Thread {
        override def run(): Unit =
          try {
            for (_ <- 1 to 100) {
              data.update(
                Map(
                  s"//target:$i" -> BspTargetInfo(
                    id = s"//target:$i",
                    kind = "scala_library",
                  )
                )
              )
              data.get(s"//target:$i")
              data.allTargets
            }
          } catch {
            case t: Throwable => failures.add(t)
          }
      }
    }
    threads.foreach(_.start())
    threads.foreach(_.join())
    assert(failures.isEmpty, s"Concurrent failures: $failures")
    assert(data.allTargets.nonEmpty)
  }
}
