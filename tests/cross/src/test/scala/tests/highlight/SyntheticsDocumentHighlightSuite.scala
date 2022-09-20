package tests.highlight

import tests.BaseDocumentHighlightSuite

class SyntheticsDocumentHighlightSuite extends BaseDocumentHighlightSuite {

  check(
    "advanced1",
    """
      |object Main {
      |  for { 
      |    abc <- Option(1) 
      |    one = 1
      |    <<a@@dd>> = one + abc
      |} yield { 
      |   <<add>>.toString.toList.map(_.toChar)
      |  }
      |}""".stripMargin,
  )

  check(
    "advanced2",
    """
      |object Main {
      |  for { 
      |    abc <- Option(1) 
      |    one = 1
      |    <<add>> = one + abc
      |} yield { 
      |   <<ad@@d>>.toString.toList.map(_.toChar)
      |  }
      |}""".stripMargin,
  )

  check(
    "advanced3",
    """
      |object Main {
      |  for { 
      |    <<a@@bc>> <- Option(1) 
      |    one = 1
      |    add = one + <<abc>>
      |} yield { 
      |   <<abc>>.toString.toList.map(_.toChar)
      |  }
      |}""".stripMargin,
  )

  check(
    "advanced4",
    """
      |object Main {
      |  for { 
      |    <<abc>> <- Option(1) 
      |    one = 1
      |    add = one + <<a@@bc>>
      |} yield { 
      |   <<abc>>.toString.toList.map(_.toChar)
      |  }
      |}""".stripMargin,
  )

  check(
    "advanced5",
    """
      |object Main {
      |  for { 
      |    <<abc>> <- Option(1) 
      |    one = 1
      |    add = one + <<abc>>
      |} yield { 
      |   <<ab@@c>>.toString.toList.map(_.toChar)
      |  }
      |}""".stripMargin,
  )

}
