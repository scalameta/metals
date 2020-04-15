package tests

import java.nio.file.Paths
import scala.collection.mutable

object TestingWorkspaceSearch {
  def empty: TestingWorkspaceSearch = new TestingWorkspaceSearch
}

class TestingWorkspaceSearch() {
  val inputs: mutable.Map[String, String] = mutable.Map.empty[String, String]
}
