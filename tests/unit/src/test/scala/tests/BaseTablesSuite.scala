package tests

import java.nio.file.Files

import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.Tables
import scala.meta.io.AbsolutePath

abstract class BaseTablesSuite extends BaseSuite {
  MetalsLogger.updateDefaultFormat()
  var workspace: AbsolutePath = _
  var tables: Tables = _
  var time = new FakeTime
  override def beforeEach(connect: BeforeEach): Unit = {
    workspace = AbsolutePath(Files.createTempDirectory("metals"))
    time.reset()
    tables = new Tables(
      workspace,
      time,
      ClientConfiguration.Default(),
    )
    tables.connect()
  }
  override def afterEach(context: AfterEach): Unit = {
    tables.cancel()
    RecursivelyDelete(workspace)
  }
}
