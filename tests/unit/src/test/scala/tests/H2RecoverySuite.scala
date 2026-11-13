package tests

import org.h2.engine.SessionLocal
import org.h2.jdbc.JdbcConnection
import org.h2.mvstore.DataUtils
import org.h2.mvstore.MVStoreException

class H2RecoverySuite extends BaseTablesSuite {

  private def closeDatabase(): Unit = {
    val session = tables
      .connect()
      .asInstanceOf[JdbcConnection]
      .getSession()
      .asInstanceOf[SessionLocal]
    session
      .getDatabase()
      .getStore()
      .getMvStore()
      .panic(
        DataUtils.newMVStoreException(
          DataUtils.ERROR_FILE_CORRUPT,
          "simulated corruption",
        )
      )
  }

  test("recover-closed-database") {
    val buildServers = tables.buildServers
    assertDiffEqual(buildServers.chooseServer("bill"), 1)
    val oldConnection = tables.connect()

    intercept[MVStoreException](closeDatabase())

    assertEquals(buildServers.selectedServer(), None)
    assert(tables.connect() ne oldConnection)
    assertEquals(buildServers.selectedServer(), None)
    assertDiffEqual(buildServers.chooseServer("bob"), 1)
    assertEquals(buildServers.selectedServer(), Some("bob"))
  }
}
