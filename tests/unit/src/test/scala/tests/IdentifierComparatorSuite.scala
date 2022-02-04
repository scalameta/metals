package tests

import scala.meta.internal.pc.IdentifierComparator

class IdentifierComparatorSuite extends munit.FunSuite {

  implicit val ord: Ordering[String] = (s1, s2) =>
    IdentifierComparator.compare(s1, s2)

  test("functions") {
    val sorted = List("Function0", "Function10", "Function1").sorted
    val expected = List("Function0", "Function1", "Function10")
    assertEquals(sorted, expected)
  }

  test("no-digits") {
    val sorted = List("Some", "Option", "Either").sorted
    val expected = List("Either", "Option", "Some")
    assertEquals(sorted, expected)
  }

  test("integers") {
    val sorted = List("04", "004", "4", "0").sorted
    val expected = List("0", "4", "04", "004")
    assertEquals(sorted, expected)
  }

  test("leading-zeros-only") {
    val sorted = List("00000", "0", "000").sorted
    val expected = List("0", "000", "00000")
    assertEquals(sorted, expected)
  }

  test("leading-zeros-mixed") {
    val sorted = List("00005", "4", "003").sorted
    val expected = List("003", "4", "00005")
    assertEquals(sorted, expected)
  }

  test("different-size") {
    val sorted = List("4400", "450", "40", "5").sorted
    val expected = List("5", "40", "450", "4400")
    assertEquals(sorted, expected)
  }

  test("same-size") {
    val sorted = List("131", "121").sorted
    val expected = List("121", "131")
    assertEquals(sorted, expected)
  }

  test("sort-long-numbers") {
    val sorted =
      List("File30110521182112346", "File0", "File30110521182112345").sorted
    val expected =
      List("File0", "File30110521182112345", "File30110521182112346")
    assertEquals(sorted, expected)
  }

}
