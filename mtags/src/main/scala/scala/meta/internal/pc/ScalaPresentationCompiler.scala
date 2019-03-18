package scala.meta.internal.pc

import java.io.File
import java.nio.file.Path
import java.util
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.logging.Logger
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter
import scala.meta.pc.PresentationCompilerConfig
import scala.util.Properties

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContext = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl()
) extends PresentationCompiler {
  val logger = Logger.getLogger(classOf[ScalaPresentationCompiler].getName)
  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)
  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler =
    copy(ec = ExecutionContext.fromExecutorService(executorService))
  override def withScheduledExecutorService(
      sh: ScheduledExecutorService
  ): PresentationCompiler =
    copy(sh = Some(sh))

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler =
    copy(config = config)
  def this() = this(buildTargetIdentifier = "")

  val access = new CompilerAccess(sh, () => newCompiler())(ec)
  override def shutdown(): Unit = {
    access.shutdown()
  }

  override def restart(): Unit = {
    // NOTE(olafur) turns out that `shutdown()` has the same effect as `restart()`
    // implementation-wise.
    shutdown()
  }

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler = {
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala,
      options = options.asScala.toList
    )
  }

  def emptyCompletion: CompletionList = {
    val items = new CompletionList(Nil.asJava)
    items.setIsIncomplete(true)
    items
  }
  override def complete(params: OffsetParams): CompletionList =
    access.withCompiler(emptyCompletion, params.token) { global =>
      new CompletionProvider(global, params).completions()
    }
  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletionItem =
    // NOTE(olafur): we use a "shared" compiler instance here because
    // we don't typecheck any sources, we only poke into the symbol table.
    // If we used a shared compiler then we risk hitting `Thread.interrupt`,
    // which can close open `*-sources.jar` files containing docstrings that
    // we read in `completionItem/resolve`.
    access.withSharedCompiler(item) { global =>
      new CompletionItemResolver(global).resolve(item, symbol)
    }

  override def signatureHelp(params: OffsetParams): SignatureHelp =
    access.withCompiler(new SignatureHelp(), params.token) { global =>
      new SignatureHelpProvider(global).signatureHelp(params)
    }

  def newCompiler(): MetalsGlobal = {
    val classpath = this.classpath.mkString(File.pathSeparator)
    val vd = new VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.Ymacroexpand.value = "discard"
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = classpath
    settings.YpresentationAnyThread.value = true
    if (!Properties.versionNumberString.startsWith("2.11") &&
      Properties.versionNumberString != "2.12.4") {
      settings.processArguments(
        List("-Ycache-plugin-class-loader:last-modified"),
        processAll = true
      )
    }
    if (classpath.isEmpty) {
      settings.usejavacp.value = true
    }
    val (isSuccess, unprocessed) =
      settings.processArguments(options, processAll = true)
    require(isSuccess, unprocessed)
    require(unprocessed.isEmpty, unprocessed)
    new MetalsGlobal(
      settings,
      new StoreReporter,
      search,
      buildTargetIdentifier,
      config
    )
  }

  // ================
  // Internal methods
  // ================

  override def hoverForDebuggingPurposes(params: OffsetParams): Hover =
    access.withCompiler(new Hover(), params.token) { global =>
      new HoverProvider(global).hover(params).orNull
    }
  override def diagnosticsForDebuggingPurposes(): util.List[String] = {
    access.reporter
      .asInstanceOf[StoreReporter]
      .infos
      .iterator
      .map { info =>
        new StringBuilder()
          .append(info.pos.source.file.path)
          .append(":")
          .append(info.pos.column)
          .append(" ")
          .append(info.msg)
          .append("\n")
          .append(info.pos.lineContent)
          .append("\n")
          .append(info.pos.lineCaret)
          .toString
      }
      .filterNot(_.contains("_CURSOR_"))
      .toList
      .asJava
  }
}
