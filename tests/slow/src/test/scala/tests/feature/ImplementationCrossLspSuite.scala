package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseImplementationSuite

class ImplementationCrossLspSuite
    extends BaseImplementationSuite("implementation-cross") {

  checkSymbols(
    "seqFactory",
    """package a
      |import scala.collection.SeqFactory
      |import scala.collection.mutable
      |
      |object A extends Seq@@Factory[List] {
      |
      |  override def from[A](source: IterableOnce[A]): List[A] = ???
      |
      |  override def empty[A]: List[A] = ???
      |
      |  override def newBuilder[A]: mutable.Builder[A, List[A]] = ???
      |
      |  
      |}
      |""".stripMargin,
    """|a/A.
       |scala/collection/ClassTagSeqFactory.AnySeqDelegate#
       |scala/collection/IndexedSeq.
       |scala/collection/LinearSeq.
       |scala/collection/Seq.
       |scala/collection/SeqFactory.Delegate#
       |scala/collection/StrictOptimizedSeqFactory#
       |scala/collection/immutable/IndexedSeq.
       |scala/collection/immutable/LazyList.
       |scala/collection/immutable/LinearSeq.
       |scala/collection/immutable/List.
       |scala/collection/immutable/Queue.
       |scala/collection/immutable/Seq.
       |scala/collection/immutable/Stream.
       |scala/collection/immutable/Vector.
       |scala/collection/mutable/ArrayBuffer.
       |scala/collection/mutable/ArrayDeque.
       |scala/collection/mutable/Buffer.
       |scala/collection/mutable/IndexedBuffer.
       |scala/collection/mutable/IndexedSeq.
       |scala/collection/mutable/ListBuffer.
       |scala/collection/mutable/Queue.
       |scala/collection/mutable/Seq.
       |scala/collection/mutable/Stack.
       |scala/jdk/AnyAccumulator.
       |""".stripMargin,
    scalaVersion = V.scala3,
  )

}
