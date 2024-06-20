package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

import scala.concurrent.ExecutionContext
import scala.util.Using

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

object ManifestJar {
  def withTempManifestJar(
      classpath: Seq[Path]
  )(
      op: AbsolutePath => SystemProcess
  )(implicit ec: ExecutionContext): SystemProcess = {
    val manifestJar =
      createManifestJar(
        AbsolutePath(
          Files.createTempFile("jvm-forker-manifest", ".jar").toAbsolutePath
        ),
        classpath,
      )

    val process = op(manifestJar)
    process.complete.onComplete { case _ =>
      manifestJar.delete()
    }
    process
  }

  def createManifestJar(
      manifestJar: AbsolutePath,
      classpath: Seq[Path],
  ): AbsolutePath = {
    if (!manifestJar.exists) {
      manifestJar.touch()
      manifestJar.toNIO.toFile().deleteOnExit()
    }

    val classpathStr =
      classpath
        .map(path => URIEncoderDecoder.encode(path.toUri().toString()))
        .mkString(" ")

    val manifest = new Manifest()
    manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest.getMainAttributes.put(Attributes.Name.CLASS_PATH, classpathStr)

    val out = Files.newOutputStream(manifestJar.toNIO)
    Using.resource(new JarOutputStream(out, manifest))(identity)
    manifestJar
  }

}
