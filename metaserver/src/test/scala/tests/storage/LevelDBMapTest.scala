package tests.storage

import java.nio.file.Files
import scala.meta.languageserver.storage.FromBytes
import scala.meta.languageserver.storage.LevelDBMap
import scala.meta.languageserver.storage.ToBytes
import tests.MegaSuite

object LevelDBMapTest extends MegaSuite {
  val tmp = Files.createTempDirectory("metaserver").toFile
  tmp.deleteOnExit()
  val db = LevelDBMap.createDBThatIPromiseToClose(tmp)
  val map = LevelDBMap(db)

  test("get/put") {
    map.put("key", "value")
    assert(map.get[String, String]("key").contains("value"))
    assert(map.get[String, String]("blah").isEmpty)
  }

  test("mapValues") {
    case class User(name: String)
    object User {
      implicit val UserToBytes: ToBytes[User] =
        ToBytes.StringToBytes.map[User](_.name)
      implicit val UserFromBytes: FromBytes[User] =
        FromBytes.StringFromBytes.map[User](User.apply)
    }
    map.put[String, User]("John", User("John"))
    assert(map.get[String, User]("John").contains(User("John")))
    assert(map.get[String, User]("Susan").isEmpty)
  }

  test("mapKeys") {
    implicit val IntToBytes: ToBytes[Int] = _.toString.getBytes
    map.put(1, "2")
    assert(map.get[Int, String](1).contains("2"))
    assert(map.get[Int, String](2).isEmpty)
  }

  test("getOrElseUpdate") {
    var count = 0
    val obtained =
      map.getOrElseUpdate[String, String]("unknown", () => {
        count += 1; count.toString
      })
    assert(obtained == "1")
    val obtained2 =
      map.getOrElseUpdate[String, String]("unknown", () => {
        count += 1; count.toString
      })
    assert(obtained2 == "1")
  }

  override def utestAfterAll(): Unit = {
    db.close()
  }
}
