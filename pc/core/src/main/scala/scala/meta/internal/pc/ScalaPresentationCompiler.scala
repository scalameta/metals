package scala.meta.internal.pc

import java.io.File
import java.nio.file.Path
import java.util
import java.util.logging.Logger
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import scala.collection.JavaConverters._
import scala.meta.pc.CompletionItems
import scala.meta.pc.CompletionItems.LookupKind
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter
import scala.util.Properties

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: Seq[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch
) extends PresentationCompiler {
  val logger = Logger.getLogger(classOf[ScalaPresentationCompiler].getName)
  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)
  def this() = this(buildTargetIdentifier = "")

  val access = new CompilerAccess(logger, () => newCompiler())
  override def shutdown(): Unit = {
    access.shutdown()
  }

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler = {
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala,
      options = options.asScala
    )
  }

  override def diagnostics(): util.List[String] = {
    access.reporter
      .asInstanceOf[StoreReporter]
      .infos
      .iterator
      .map(
        info =>
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
      )
      .filterNot(_.contains("_CURSOR_"))
      .toList
      .asJava
  }

  def emptyCompletion = new CompletionItems(LookupKind.None, Nil.asJava)
  override def complete(params: OffsetParams): CompletionItems =
    access.withCompiler(emptyCompletion) { global =>
      new CompletionProvider(global, params).completions()
    }
  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletionItem =
    access.withCompiler(item) { global =>
      new CompletionItemResolver(global).resolve(item, symbol)
    }

  override def hover(params: OffsetParams): Hover =
    access.withCompiler(new Hover()) { global =>
      new HoverProvider(global).hover(params).orNull
    }

  override def signatureHelp(params: OffsetParams): SignatureHelp =
    access.withCompiler(new SignatureHelp()) { global =>
      new SignatureHelpProvider(global).signatureHelp(params)
    }

  override def symbol(params: OffsetParams): String = {
    access.withCompiler("") { global =>
      val unit = global.addCompilationUnit(
        code = params.text(),
        filename = params.filename(),
        cursor = None
      )
      val pos = unit.position(params.offset())
      global.typedTreeAt(pos).symbol.fullName
    }
  }
  // Metals officially only supports a hardcoded list of compiler
  // plugins that are known to play nicely with the presentation compiler.
  // Users who want to use Metals with other compiler plugins will need to
  // send a PR adding tests to show their compiler plugin plays nicely with
  // the presentation compiler.
  val whitelist = Array[Array[String]](
    Array("scalamacros", "paradise"),
    Array("kind-projector"),
    Array("better-monadic-for")
  )

  def isWhitelistedCompilerPlugin(option: String): Boolean =
    whitelist.exists { keywords =>
      keywords.forall(keyword => option.contains(keyword))
    }

  def newCompiler(): MetalsGlobal = {
    val classpath = this.classpath.mkString(File.pathSeparator)
    val options = this.options.iterator.filterNot { o =>
      (o.startsWith("-Xplugin") || o.startsWith("-P")) &&
      !isWhitelistedCompilerPlugin(o)
    }.toList
    val vd = new VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.Ymacroexpand.value = "discard"
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = classpath
    settings.YpresentationAnyThread.value = true
    if (!Properties.versionNumberString.startsWith("2.11")) {
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
      logger
    )
  }
}
