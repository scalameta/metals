package tests.mbt

import scala.meta.internal.metals.mbt.LMDB

class LMDBSuite extends munit.FunSuite {
  test("isSupportedOrWarn") {
    assert(LMDB.isSupportedOrWarn())
  }
}
