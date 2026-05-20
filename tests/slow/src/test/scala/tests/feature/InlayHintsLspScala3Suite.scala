package tests.feature

import scala.meta.internal.metals.BuildInfo

import tests.BaseInlayHintsLspSuite

class InlayHintsLspScala3Suite
    extends BaseInlayHintsLspSuite(
      "inlayHints-scala3",
      BuildInfo.scala3,
    ) {

  check(
    "i6379",
    expected =
      """|import cats.Applicative
         |import cats.data.Validated
         |import cats.data.ValidatedNec
         |import cats.implicits._
         |import cats.syntax.all._
         |
         |class A {
         |  type Validated[A] = ValidatedNec[Nothing, A]
         |
         |  val a = implicitly[Applicative[Validated]]/*(using catsDataApplicativeErrorForValidated<<cats/data/ValidatedInstances#catsDataApplicativeErrorForValidated().>>(catsDataSemigroupForNonEmptyChain<<cats/data/NonEmptyChainInstances#catsDataSemigroupForNonEmptyChain().>>))*/.pure(1)
         |}
         |""".stripMargin,
    dependencies = List("org.typelevel::cats-core:2.10.0"),
    config = Some(
      """|"inferredTypes": {"enable":false},
         |"implicitConversions": {"enable":false},
         |"implicitArguments": {"enable":true},
         |"typeParameters": {"enable":false},
         |"hintsInPatternMatch": {"enable":false}
         |""".stripMargin
    ),
  )
}
