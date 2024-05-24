package tests

import scala.concurrent.ExecutionContext

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.pc.PresentationCompiler

import tests.InputProperties
import tests.TestMtagsResolver
import tests.TestingClient

object TestScala3Compiler {
  def compiler(name: String, input: InputProperties)(implicit
      ec: ExecutionContext
  ): Option[PresentationCompiler] = {
    val resolver = new TestMtagsResolver()
    resolver.resolve(V.scala3) match {
      case Some(mtags: MtagsBinaries.Artifacts) =>
        val time = new FakeTime
        val client = new TestingClient(PathIO.workingDirectory, Buffers())
        val status = new WorkDoneProgress(client, time)(ec)
        val embedded = new Embedded(status)
        val pc = embedded
          .presentationCompiler(mtags)
          .newInstance(
            name,
            input.classpath.entries.map(_.toNIO).asJava,
            Nil.asJava,
          )
        Some(pc)
      case _ => None
    }

  }
}
