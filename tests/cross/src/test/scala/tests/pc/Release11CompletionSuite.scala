package tests.pc

import java.nio.file.Path

import tests.BaseCompletionSuite

class Release11CompletionSuite extends BaseCompletionSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala211
  )

  override protected def scalacOptions(classpath: Seq[Path]): Seq[String] = Seq(
    "-release",
    "11"
  )

  check(
    "keyword",
    """|object Main {
       |  "M".rep@@
       |}
       |""".stripMargin,
    // repeat method was added in JDK 11
    """|repeat(x$1: Int): String
       |replace(x$1: Char, x$2: Char): String
       |replace(x$1: CharSequence, x$2: CharSequence): String
       |replaceAll(x$1: String, x$2: String): String
       |replaceFirst(x$1: String, x$2: String): String
       |prepended(c: Char): String
       |prepended[B >: Char](elem: B): IndexedSeq[B]
       |prependedAll(prefix: String): String
       |prependedAll[B >: Char](prefix: IterableOnce[B]): IndexedSeq[B]
       |charStepper: IntStepper with Stepper.EfficientSplit
       |corresponds[B](that: IterableOnce[B])(p: (Char, B) => Boolean): Boolean
       |corresponds[B](that: Seq[B])(p: (Char, B) => Boolean): Boolean
       |reduceLeftOption[B >: Char](op: (B, Char) => B): Option[B]
       |reduceOption[B >: Char](op: (B, B) => B): Option[B]
       |reduceRightOption[B >: Char](op: (Char, B) => B): Option[B]
       |replaceAllLiterally(literal: String, replacement: String): String
       |repr: WrappedString
       |reverseMap[B](f: Char => B): IndexedSeq[B]
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|repeat(x$0: Int): String
           |replace(x$0: CharSequence, x$1: CharSequence): String
           |replace(x$0: Char, x$1: Char): String
           |replaceAll(x$0: String, x$1: String): String
           |replaceFirst(x$0: String, x$1: String): String
           |replaceAllLiterally(literal: String, replacement: String): String
           |repr: C
           |""".stripMargin,
      "2.12" ->
        """|repeat(x$1: Int): String
           |replace(x$1: Char, x$2: Char): String
           |replace(x$1: CharSequence, x$2: CharSequence): String
           |replaceAll(x$1: String, x$2: String): String
           |replaceFirst(x$1: String, x$2: String): String
           |repr: String
           |replaceAllLiterally(literal: String, replacement: String): String
           |repr: WrappedString
           |corresponds[B](that: GenSeq[B])(p: (Char, B) => Boolean): Boolean
           |reduceLeftOption[B >: Char](op: (B, Char) => B): Option[B]
           |reduceOption[A1 >: Char](op: (A1, A1) => A1): Option[A1]
           |reduceRightOption[B >: Char](op: (Char, B) => B): Option[B]
           |reverseMap[B, That](f: Char => B)(implicit bf: CanBuildFrom[String,B,That]): That
           |""".stripMargin
    )
  )

}
