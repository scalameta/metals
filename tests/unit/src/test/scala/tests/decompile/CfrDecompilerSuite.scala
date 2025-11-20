package tests.decompile

import java.net.URI

import scala.concurrent.ExecutionContext.Implicits.global

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.decompile.CfrDecompiler
import scala.meta.internal.mtags.BuildInfo
import scala.meta.io.AbsolutePath

import coursierapi.Dependency
import coursierapi.Fetch
import tests.BaseSuite

class CfrDecompilerSuite extends BaseSuite {
  test("decompile-scala-library-class") {
    // Get scala-library jar path using coursier (similar to Library.fetch)
    val scalaVersion = BuildInfo.scalaCompilerVersion
    val scalaLibraryDep =
      Dependency.of("org.scala-lang", "scala-library", scalaVersion)

    val scalaLibraryJar = Fetch
      .create()
      .withDependencies(scalaLibraryDep.withTransitive(false))
      .fetch()
      .asScala
      .headOption
      .map(f => AbsolutePath(f.toPath))
      .getOrElse(fail("Could not fetch scala-library jar"))

    // Decompile a well-known Scala class (e.g., scala.Option)
    val optionClassUri = URI.create(
      s"jar:file:${scalaLibraryJar.toNIO.toUri.getPath}!/scala/Function0.class"
    )

    val decompiler = new CfrDecompiler()

    for {
      Right(decompiledcode) <- decompiler.decompilePath(
        optionClassUri.toAbsolutePath,
        Nil,
      )
    } yield {

      // Verify decompiled output contains key elements
      assert(
        decompiledcode.contains("public interface Function0<R>"),
        s"Should contain class name 'Function0', instead got:\n${decompiledcode}",
      )
      assert(decompiledcode.contains("scala"), "Should contain package name")
    }
  }

  test("decompile-non-existent-class") {
    val decompiler = new CfrDecompiler()
    for {
      Left(error) <- decompiler.decompilePath(
        URI.create("file:///foo/bar/non-existent-class.class").toAbsolutePath,
        Nil,
      )
    } yield {
      assert(error.contains("No such file"), error)
    }
  }
}
