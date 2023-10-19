package tests.feature

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.pc.PresentationCompiler

import tests.BaseSyntheticDecorationsExpectSuite
import tests.FakeTime
import tests.InputProperties
import tests.TestMtagsResolver
import tests.TestingClient

class SyntheticDecorationsScala3ExpectSuite(
) extends BaseSyntheticDecorationsExpectSuite(
      "decorations3",
      InputProperties.scala3(),
    ) {
  override val compiler: PresentationCompiler = {
    val resolver = new TestMtagsResolver()
    resolver.resolve(V.scala3) match {
      case Some(mtags: MtagsBinaries.Artifacts) =>
        val time = new FakeTime
        val client = new TestingClient(PathIO.workingDirectory, Buffers())
        val status = new StatusBar(
          client,
          time,
          ProgressTicks.dots,
          ClientConfiguration.default,
        )(munitExecutionContext)
        val embedded = new Embedded(status)
        embedded
          .presentationCompiler(mtags, mtags.jars)
          .newInstance(
            "tokens",
            input.classpath.entries.map(_.toNIO).asJava,
            Nil.asJava,
          )
      case _ => fail(s"Could not load ${V.scala3} presentation compiler")
    }

  }
}
