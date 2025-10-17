package tests

import java.nio.file.Files

import scala.concurrent.ExecutionContext

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler

import tests.InputProperties
import tests.TestMtagsResolver
import tests.TestingClient

object TestScala3Compiler {
  def compiler(name: String, input: InputProperties)(implicit
      ec: ExecutionContext
  ): Option[PresentationCompiler] = {
    val resolver = new TestMtagsResolver(checkCoursier = true)
    resolver.resolve(V.scala3) match {
      case Some(mtags: MtagsBinaries.Artifacts) =>
        val time = new FakeTime
        val client = new TestingClient(PathIO.workingDirectory, Buffers())
        val status = new WorkDoneProgress(client, time)(ec)
        val tmp = Files.createTempDirectory("metals")
        tmp.toFile.deleteOnExit()
        val workspace = AbsolutePath(tmp.toFile)
        val embedded = new Embedded(workspace, status)
        val pc = embedded
          .presentationCompiler(mtags)
          .newInstance(
            name,
            input.classpath.entries.map(_.toNIO).asJava,
            Nil.asJava,
            Nil.asJava,
          )
        Some(pc)
      case _ => None
    }

  }
}
