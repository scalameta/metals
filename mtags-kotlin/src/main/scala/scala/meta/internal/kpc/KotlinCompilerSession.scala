package scala.meta.internal.kpc

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.analysis.api.projectStructure.DanglingFilesKt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilder
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilderKt
import org.jetbrains.kotlin.analysis.project.structure.builder.KaLibraryModuleBuilderKt
import org.jetbrains.kotlin.analysis.project.structure.builder.KaModuleContainerBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.KaSdkModuleBuilderKt
import org.jetbrains.kotlin.analysis.project.structure.builder.KaSourceModuleBuilderKt
import org.jetbrains.kotlin.analysis.project.structure.builder.KtLibraryModuleBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.KtSdkModuleBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.KtSourceModuleBuilder
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * An Analysis API session for one build target. Sources come from the build
 * target and from the open buffers, libraries from the classpath.
 *
 * The session reads files from the editor buffers, never from disk for an
 * open file. A file deleted or changed in a buffer is therefore seen by every
 * other file of the build target, which a dangling file copy cannot do.
 * Other build targets read the file from disk.
 */
final class KotlinCompilerSession(
    classpath: Seq[Path],
    sources: () => Seq[Path],
    jdkHome: Path
) {
  final class Snapshot(
      val disposable: Disposable,
      val project: Project,
      val sourceModule: KaModule,
      val texts: Map[Path, String],
      val files: Map[Path, KtFile],
      paths: Map[VirtualFile, Path],
      val diskStamps: Map[Path, Option[FileTime]]
  ) {

    /** Copies of edited files, analysed in place of the files in the session. */
    private[KotlinCompilerSession] val copies = mutable.Map.empty[Path, KtFile]

    def isCopy(file: KtFile): Boolean = copies.values.exists(_ eq file)

    def pathOf(file: PsiFile): Option[Path] =
      copies
        .collectFirst { case (path, copy) if copy eq file => path }
        .orElse(Option(file.getVirtualFile).flatMap(paths.get))
  }

  private var snapshot: Option[Snapshot] = None
  private val buffers = mutable.Map.empty[Path, String]
  private var isShutdown = false

  /**
   * Records `text` as the content of the file at `path`. Call it in the
   * order of the requests: requests run later, in any order.
   */
  def update(path: Path, text: String): Unit = synchronized {
    if (!isShutdown) buffers(normalize(path)) = text
  }

  /**
   * Runs `f` on the file at `path`, when `text` is still its latest content.
   *
   * An edit inside a function body or an initializer keeps the session and
   * analyses a copy of the file. Other files see the edit after the next
   * rebuild: a request for another file, or an edit of a declaration.
   */
  def withFile[T](path: Path, text: String)(
      f: (KtFile, Snapshot) => T
  ): Option[T] = synchronized {
    val file = normalize(path)
    if (isShutdown || !buffers.get(file).contains(text)) None
    else {
      val stamps = diskStamps(buffers.keySet.toSet)
      val current = snapshot.filter(s =>
        stamps == s.diskStamps -- buffers.keys && buffers.forall {
          case (p, t) =>
            p == file || s.texts.get(p).contains(t)
        }
      )
      current.flatMap(s => s.files.get(file).map(s -> _)) match {
        case Some((s, kt)) if s.texts.get(file).contains(text) =>
          Some(f(kt, s))
        case Some((s, kt)) =>
          val copy = s.copies
            .get(file)
            .filter(_.getText == text)
            .getOrElse(newCopy(s, kt, text))
          if (outline(copy) == outline(kt)) {
            s.copies(file) = copy
            Some(f(copy, s))
          } else rebuild().files.get(file).map(f(_, snapshot.get))
        case None => rebuild().files.get(file).map(f(_, snapshot.get))
      }
    }
  }

  private def rebuild(): Snapshot = {
    close()
    val fresh = build()
    snapshot = Some(fresh)
    fresh
  }

  private def newCopy(s: Snapshot, original: KtFile, text: String): KtFile = {
    val copy =
      new KtPsiFactory(s.project, false).createFile(original.getName, text)
    DanglingFilesKt.setContextModule(copy, s.sourceModule)
    copy
  }

  /**
   * The tokens of `file` outside function bodies and initializers, without
   * spaces. Files with equal outlines declare the same things.
   *
   * ponytail: the type of a declaration without an explicit type comes from
   * its body. When that type reaches this file through another file, the
   * copy sees the old type until the next rebuild.
   */
  private def outline(file: KtFile): List[String] = {
    val bodies: Set[PsiElement] = PsiTreeUtil
      .findChildrenOfAnyType(
        file,
        classOf[KtDeclarationWithBody],
        classOf[KtProperty],
        classOf[KtAnonymousInitializer]
      )
      .asScala
      .toList
      .flatMap {
        case d: KtDeclarationWithBody => Option(d.getBodyExpression).toList
        case p: KtProperty =>
          Option(p.getInitializer).toList ++ Option(p.getDelegateExpression)
        case i: KtAnonymousInitializer => Option(i.getBody).toList
        case _ => Nil
      }
      .toSet
    val tokens = mutable.ListBuffer.empty[String]
    def visit(element: PsiElement): Unit =
      if (!bodies.contains(element)) {
        val children =
          Iterator
            .iterate(element.getFirstChild)(_.getNextSibling)
            .takeWhile(_ != null)
        if (element.getFirstChild == null) {
          if (!element.isInstanceOf[PsiWhiteSpace]) tokens += element.getText
        } else children.foreach(visit)
      }
    visit(file)
    tokens.toList
  }

  def didClose(path: Path): Unit = synchronized {
    val file = normalize(path)
    buffers.remove(file)
    // A closed buffer can have edits that were never saved.
    if (
      snapshot
        .exists(s => s.texts.contains(file) && s.texts.get(file) != read(file))
    )
      close()
  }

  /** Drops the session, so the next request reads the sources again. */
  def restart(): Unit = synchronized(close())

  /** Drops the session for good: later requests get no result. */
  def shutdown(): Unit = synchronized {
    isShutdown = true
    buffers.clear()
    close()
  }

  private def normalize(path: Path): Path = path.toAbsolutePath.normalize

  private def close(): Unit = {
    snapshot.foreach(s => Disposer.dispose(s.disposable))
    snapshot = None
  }

  /**
   * Modification times of the sources read from disk. A change shows an
   * edit outside the editor, such as `git checkout`.
   *
   * ponytail: reads the time of every source on each request (a few ms for
   * 600 files); use the file watcher of Metals if that gets slow.
   */
  private def diskStamps(open: Set[Path]): Map[Path, Option[FileTime]] =
    sources()
      .map(normalize)
      .filter(p => isSource(p) && !open.contains(p))
      .map(p => p -> Try(Files.getLastModifiedTime(p)).toOption)
      .toMap

  private def build(): Snapshot = {
    val stamps = diskStamps(buffers.keySet.toSet)
    val fromDisk = stamps.keys.toSeq.flatMap(p => read(p).map(p -> _))
    val texts = fromDisk.toMap ++ buffers
    val virtualFiles = texts.map { case (path, text) =>
      val fileType =
        if (path.toString.endsWith(".java")) JavaFileType.INSTANCE
        else KotlinFileType.INSTANCE
      path -> (new LightVirtualFile(
        path.getFileName.toString,
        fileType,
        text
      ): VirtualFile)
    }
    val platform = JvmPlatforms.INSTANCE.getDefaultJvmPlatform
    val libraries = classpath.filter(p => Files.exists(p))
    val disposable = Disposer.newDisposable("metals-kotlin")
    try {
      var sourceModule: KaModule = null
      val session = StandaloneAnalysisAPISessionBuilderKt
        .buildStandaloneAnalysisAPISession(
          disposable,
          false,
          Kt.unitFn[StandaloneAnalysisAPISessionBuilder] { builder =>
            builder.buildKtModuleProvider(
              Kt.unitFn[KaModuleContainerBuilder] { modules =>
                modules.setPlatform(platform)
                val jdk = modules.addModule(
                  KaSdkModuleBuilderKt.buildKtSdkModule(
                    modules,
                    Kt.unitFn[KtSdkModuleBuilder] { sdk =>
                      sdk.addBinaryRootsFromJdkHome(jdkHome, false)
                      sdk.setPlatform(platform)
                      sdk.setLibraryName("jdk")
                    }
                  )
                )
                val deps = modules.addModule(
                  KaLibraryModuleBuilderKt.buildKtLibraryModule(
                    modules,
                    Kt.unitFn[KtLibraryModuleBuilder] { lib =>
                      lib.addBinaryRoots(libraries.asJava)
                      lib.setPlatform(platform)
                      lib.setLibraryName("classpath")
                    }
                  )
                )
                sourceModule = modules.addModule(
                  KaSourceModuleBuilderKt.buildKtSourceModule(
                    modules,
                    Kt.unitFn[KtSourceModuleBuilder] { src =>
                      src.addSourceVirtualFiles(
                        virtualFiles.values.asJavaCollection
                      )
                      src.setPlatform(platform)
                      src.setModuleName("main")
                      src.addRegularDependency(jdk)
                      src.addRegularDependency(deps)
                    }
                  )
                )
              }
            )
          }
        )
      val pathOf = virtualFiles.map(_.swap)
      val files = session.getModulesWithFiles.asScala.values
        .flatMap(_.asScala)
        .collect {
          case kt: KtFile if pathOf.contains(kt.getVirtualFile) =>
            pathOf(kt.getVirtualFile) -> kt
        }
        .toMap
      new Snapshot(
        disposable,
        session.getProject,
        sourceModule,
        texts,
        files,
        pathOf,
        stamps
      )
    } catch {
      case e: Throwable =>
        Disposer.dispose(disposable)
        throw e
    }
  }

  private def isSource(path: Path): Boolean = {
    val name = path.toString
    name.endsWith(".kt") || name.endsWith(".java")
  }

  private def read(path: Path): Option[String] =
    Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toOption
}
