package tests

import scala.meta.internal.pc.IdentifierComparator

class IdentifierComparatorSuite extends munit.FunSuite {

  implicit val ord: Ordering[String] = (s1, s2) =>
    IdentifierComparator.compare(s1, s2)

  test("sort-functions") {
    val sorted = List("Function0", "Function10", "Function1").sorted
    val expected = List("Function0", "Function1", "Function10")
    assertEquals(sorted, expected)
  }

  test("sort-integers") {
    val sorted = List("04", "004", "4").sorted
    val expected = List("4", "04", "004")
    assertEquals(sorted, expected)
  }

  test("sort-octals") {
    val sorted = List("0x01", "0x1").sorted
    val expected = List("0x1", "0x01")
    assertEquals(sorted, expected)
  }

  test("sort-long-numbers") {
    val sorted = List("File30110521182112346", "File30110521182112345").sorted
    val expected = List("File30110521182112345", "File30110521182112346")
    assertEquals(sorted, expected)
  }

}
