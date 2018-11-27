package tests

import java.nio.file.Files
import scala.meta.internal.metals.Tables
import scala.meta.io.AbsolutePath

abstract class BaseTablesSuite extends BaseSuite {
  var workspace: AbsolutePath = _
  var tables: Tables = _
  var time = new FakeTime
  override def utestBeforeEach(path: Seq[String]): Unit = {
    workspace = AbsolutePath(Files.createTempDirectory("metals"))
    time.reset()
    tables = Tables.forWorkspace(workspace, time)
  }
  override def utestAfterEach(path: Seq[String]): Unit = {
    tables.cancel()
    RecursivelyDelete(workspace)
  }
}
