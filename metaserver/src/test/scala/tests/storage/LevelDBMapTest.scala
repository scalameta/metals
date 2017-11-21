package tests.storage

import java.nio.file.Files
import scala.meta.languageserver.storage.LevelDBMap
import tests.MegaSuite

object LevelDBMapTest extends MegaSuite {
  val tmp = Files.createTempDirectory("metaserver").toFile
  tmp.deleteOnExit()
  val db = LevelDBMap.createDBThatIPromiseToClose(tmp)
  val map = LevelDBMap[String](db)

  test("get/put") {
    map.put("key", "value")
    assert(map.get("key").contains("value"))
    assert(map.get("blah").isEmpty)
  }

  test("mapValues") {
    case class User(name: String)
    val userMap = map.mapValues[User](User.apply, _.name)
    userMap.put("John", User("John"))
    assert(userMap.get("John").contains(User("John")))
    assert(userMap.get("Susan").isEmpty)
  }

  test("mapKeys") {
    val intMap = map.mapKeys[Int](_.toInt, _.toString)
    intMap.put(1, "2")
    assert(intMap.get(1).contains("2"))
    assert(intMap.get(2).isEmpty)
  }

  test("getOrElseUpdate") {
    var count = 0
    val obtained =
      map.getOrElseUpdate("unknown", () => { count += 1; count.toString })
    assert(obtained == "1")
    val obtained2 =
      map.getOrElseUpdate("unknown", () => { count += 1; count.toString })
    assert(obtained2 == "1")
  }

  override def utestAfterAll(): Unit = {
    db.close()
  }
}
