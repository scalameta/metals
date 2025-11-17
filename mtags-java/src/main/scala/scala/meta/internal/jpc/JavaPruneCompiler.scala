package scala.meta.internal.jpc

import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.pc

import com.sun.source.util.JavacTask
import org.slf4j.Logger

/**
 * The Java "Prune" Compiler is a thin wrapper around javac that is optimized for
 * compiling individual files. Concretely, it does two things beyond normal compilation:
 *
 * - Uses a virtual file system (aka. java file manager) allowing the compiler
 *   to load symbols from anywhere in the codebase regardless of where they're located.
 *   In javac (and scalac), the -sourcepath flag employs the same trick but they require
 *   all sources to be organized in a directory structure where the relative path mirrors
 *   the package structure. With the prune compiler, you can place sources anywhere regardless
 *   of whether the package name matches the directory structure or not.
 * - Uses a custom Java compiler plugin to remove the bodies of methods and
 *   fields for dependency sources on the classpath. This stops the compiler
 *   from recursively "crawling" the codebase. We are only trying to compile the minimal
 *   amount of code needed to attribute (aka. typecheck) the main source file.
 */
class JavaPruneCompiler(
    val logger: Logger,
    val semanticdbFileManager: pc.SemanticdbFileManager,
    embedded: pc.EmbeddedClient
) extends Closeable {
  private lazy val headerCompiler =
    embedded.javaHeaderCompilerPluginJarPath()

  val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
  private val standardFileManager =
    compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)
  val fileManager = new PruneCompilerFileManager(
    standardFileManager,
    semanticdbFileManager,
    logger
  )
  // HACK: For a variety of reasons, the JavaFileObject.toUri() method can't
  // always mirror the LSP URIs we use in Metals. For example, JDK source
  // seemingly to be normal file:/// URIs to work with the --patch-module option.
  // It's probably possible to work around this by overriding more methods in out
  // custom file manager but for now, we store the mapping to the original URI here.
  // We clear this map for every new compile request so it should at most only
  // ever contain a single entry.
  val originalURIs = new mutable.HashMap[JavaFileObject, String]()

  val patches = new mutable.HashSet[PatchedModule]()

  override def close(): Unit = {
    fileManager.close()
  }

  def compileOptions(
      files: List[PruneJavaFile],
      classpath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil
  ): List[String] = {
    val finalClasspath = classpath :+ headerCompiler
    val options = List.newBuilder[String]
    options ++= List(
      "-d",
      embedded.targetDir().toString(),
      "-parameters",
      // NOTE(olafurpg): I think we can remove this?
      "-XDdiags.showEndPos=true",
      // Critical! This enables our custom prune file manager
      "-sourcepath",
      "",
      "-proc:none",
      // javac internal packages
      "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.resources=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
      // com.sun.source public APIs (sometimes need explicit exports)
      "--add-exports=jdk.compiler/com.sun.source.doctree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.source.tree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.source.util=ALL-UNNAMED",
      // java.base internal utilities
      "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
      "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
      "--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED",
      "--add-exports=java.base/sun.security.util=ALL-UNNAMED",
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
      // javadoc tools
      "--add-exports=jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED",
      "--add-exports=jdk.javadoc/com.sun.tools.javadoc.main=ALL-UNNAMED"
    )
    if (finalClasspath.nonEmpty) {
      options += "-classpath"
      options += finalClasspath.mkString(File.pathSeparator)
    }
    options ++= processExtraOptions(extraOptions, files)
    // Add --patch-module option if we're compiling sources from the JDK.
    for {
      file <- files
      patch <- file.patch
      if !patches.contains(patch)
    } {
      // The JavaCompiler instance is stateful and only allows you to define
      // the --patch-module option once per module. It throws an exception if
      // you define conflicting patches, and we automatically handle this by
      // throwing a DuplicatePatchModuleException, which triggers a restart of
      // the JavaCompiler instance.
      patches.add(patch)
      options ++= patch.asOptions
    }
    options.result()
  }

  /**
   * Returns a parsed but not analyzed compile task. Call `.withAnalyze()` to
   * also run the analysis phase.
   *
   * Throws an error if the compiler fails to parse the provided options for any
   * reason.
   *
   * Logs warnings if the compiler reports diagnostics *before* parsing, which
   * often indicates a configuration problem. It's not displayed to the user
   * because it's not an actionable error, but might be helpful debugging why
   * something is not working for a user.
   */
  def compileTask(
      params: pc.VirtualFileParams,
      classpath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil
  ): JavaSourceCompile = {
    batchCompileTask(List(params), classpath, extraOptions)
  }

  def batchCompileTask(
      params: List[pc.VirtualFileParams],
      classpath: Seq[Path] = Nil,
      extraOptions: List[String] = Nil
  ): JavaSourceCompile = {
    require(params.nonEmpty, "params must be non-empty")
    originalURIs.clear()
    val files = params.map { p =>
      val result =
        PruneJavaFile.fromParams(p, embedded, standardFileManager)
      originalURIs.put(result.source, p.uri().toString())
      result
    }
    val store = new JavaCompileTaskListener()
    val options = compileOptions(files, classpath, extraOptions)
    val task =
      try
        compiler.getTask(
          store.sout,
          fileManager,
          store,
          options.asJava,
          null,
          files.map(_.source).asJava
        )
      catch {
        case e: RuntimeException
            if e
              .getMessage()
              .contains("--patch-module specified more than once") =>
          // This is a known issue that happens when we reuse the same JavaPruneCompiler instance
          // when switching between virtual files and readonly sources. The compiler is stateful and
          // preserves the --patch-module option between tasks. The fix is to try again with a fresh instance,
          // which is what happens when we throw this exception.
          throw new DuplicatePatchModuleException(e)
      }
    // Log reported diagnostics *before* we even parse. These are usually high-signal
    store.diagnostics.iterator.foreach { diagnostic =>
      logger.warn(
        s"JavaMetalsGlobal: diagnostic reported before parsing - ${diagnostic}"
      )
    }
    val stdout = store.sout.toString()
    if (stdout.nonEmpty) {
      val filesStr = files.map(_.source.toUri()).mkString(", ")
      logger.info(s"JavaMetalsGlobal: stdout for files $filesStr - $stdout")
    }
    val jtask = task.asInstanceOf[JavacTask]
    val elems = jtask.parse().asScala
    val it = elems.iterator
    if (it.hasNext) {
      val cu = it.next()
      val rest = it.toSeq
      JavaSourceCompile(jtask, store, cu, rest)
    } else {
      if (store.diagnostics.isEmpty) {
        throw new RuntimeException(
          s"Expected single compilation unit but got none. The compiler reported no diagnostics. The print writer returned '${store.sout}'."
        )
      }
      val diagnostics = store.diagnostics
        .map(d =>
          s"${d.getKind} Source=${d.getSource()} Message=${d.getMessage(null)}"
        )
        .mkString("\n  ")
      throw new RuntimeException(
        s"Failed to get a compilation unit from the Java compiler. Errors:\n  ${diagnostics}"
      )
    }
  }

  private def processExtraOptions(
      extraOptions: List[String],
      files: List[PruneJavaFile]
  ): List[String] = {
    val options = List.newBuilder[String]
    if (headerCompiler == null) {
      throw new RuntimeException(
        "Header compiler is not enabled. To fix this problem, make sure you initialize the presentation compiler with a non-empty EmbeddedClient."
      )
    }
    // Tell the header compiler to keep the bodies of the source file but
    // remove the bodies for all other sources on the sourcepath.
    val headerCompilerOption = files.iterator
      .map(file => "-keep-bodies:" + file.source.getName())
      .mkString("-Xplugin:MetalsHeaderCompiler ", " ", "")
    options += headerCompilerOption
    var isAnnotationPath = false
    extraOptions.foreach { extraOption =>
      val nextOption: String =
        if (extraOption == "-processorpath") {
          isAnnotationPath = true
          extraOption
        } else if (isAnnotationPath) {
          // Processing the option following "-processorpath"
          isAnnotationPath = false
          val processorPath =
            List(headerCompiler.toString(), extraOption)
              .mkString(File.pathSeparator)
          processorPath
        } else if (extraOption.startsWith("-processorpath=")) {
          s"${extraOption}${File.pathSeparator}${headerCompiler}"
        } else {
          extraOption
        }
      options += nextOption
    }
    options.result()
  }

}
