package scala.meta.internal.metals

import java.nio.charset.Charset

import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.scalacli.ScalaCliServers
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Semanticdbs.FoundSemanticDbPath
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

/**
 * Reads SemanticDBs from disk that are produces by the semanticdb-scalac compiler plugin.
 */
final class FileSystemSemanticdbs(
    buildTargets: BuildTargets,
    charset: Charset,
    mainWorkspace: AbsolutePath,
    fingerprints: Md5Fingerprints,
    scalaCliServers: => ScalaCliServers,
) extends Semanticdbs {

  override def textDocument(file: AbsolutePath): TextDocumentLookup = {
    if (
      (!file.toLanguage.isScala && !file.toLanguage.isJava) ||
      file.toNIO.getFileSystem != mainWorkspace.toNIO.getFileSystem ||
      scalaCliServers.loadedExactly(
        file
      ) // scala-cli single files, interactive is used for those
    ) {
      TextDocumentLookup.NotFound(file)
    } else {

      val paths = for {
        buildTarget <- buildTargets.inverseSources(file)
        workspace <- buildTargets.workspaceDirectory(buildTarget)
        targetroot <- {
          val javaRoot =
            if (file.toLanguage.isJava)
              buildTargets.javaTargetRoot(buildTarget)
            else None
          javaRoot orElse buildTargets.scalaTargetRoot(buildTarget)
        }
      } yield {
        if (!targetroot.exists)
          scribe.debug(s"Target root $targetroot does not exist")
        val optScalaVersion =
          if (file.toLanguage.isJava) None
          else buildTargets.scalaTarget(buildTarget).map(_.scalaVersion)

        (workspace, targetroot, optScalaVersion)
      }

      paths match {
        case Some((ws, targetroot, optScalaVersion)) =>
          try {
            Semanticdbs.loadTextDocument(
              file,
              ws,
              optScalaVersion,
              charset,
              fingerprints,
              semanticdbRelativePath =>
                findSemanticDb(semanticdbRelativePath, targetroot, file, ws),
              (warn: String) => scribe.warn(warn),
            )
          } catch {
            case e: java.nio.file.NoSuchFileException =>
              scribe.debug(
                s"semantic db not found: $file"
              )
              scribe.trace(e)
              TextDocumentLookup.NotFound(file)
          }
        case None =>
          TextDocumentLookup.NotFound(file)
      }
    }
  }

  def findSemanticDb(
      semanticdbRelativePath: RelativePath,
      targetroot: AbsolutePath,
      file: AbsolutePath,
      workspace: AbsolutePath,
  ): Option[FoundSemanticDbPath] = {
    val semanticdbpath = targetroot.resolve(semanticdbRelativePath)
    if (semanticdbpath.isFile) Some(FoundSemanticDbPath(semanticdbpath, None))
    else if (targetroot.isFile && targetroot.toString().endsWith(".jar")) {
      val jarFS = PlatformFileIO.newJarFileSystem(targetroot, create = false)
      val jPath = jarFS.getPath("/" + semanticdbRelativePath.toString())
      Some(FoundSemanticDbPath(AbsolutePath(jPath), None))
    } else {
      // needed in case sources are symlinked,
      val result = for {
        sourceRoot <- buildTargets.originalInverseSourceItem(file)
        relativeSourceRoot = sourceRoot.toRelative(workspace)
        relativeFile = file.toRelative(sourceRoot.dealias)
        fullRelativePath = relativeSourceRoot.resolve(relativeFile)
        alternativeRelativePath = SemanticdbClasspath.fromScalaOrJava(
          fullRelativePath
        )
        alternativeSemanticdbPath = targetroot.resolve(
          alternativeRelativePath
        )
        if alternativeSemanticdbPath.isFile
      } yield FoundSemanticDbPath(
        alternativeSemanticdbPath,
        Some(fullRelativePath),
      )
      if (result.isEmpty)
        scribe.debug(
          s"No text document found at for $file expected at ${semanticdbpath}"
        )
      result
    }

  }

}
