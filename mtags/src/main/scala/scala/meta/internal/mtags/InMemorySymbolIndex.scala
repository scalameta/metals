package scala.meta.internal.mtags

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.meta.inputs.Input
import scala.meta.internal.io.PathIO
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Enrichments._
import scala.meta.internal.mtags.DefinitionAlternatives
import scala.meta.internal.io._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.mtags.Mtags

case class InMemorySymbolIndex(
    toplevels: mutable.Map[String, AbsolutePath] = mutable.Map.empty,
    definitions: mutable.Map[String, AbsolutePath] = mutable.Map.empty,
    references: mutable.Map[String, mutable.Set[AbsolutePath]] =
      mutable.Map.empty
) extends SymbolIndex {
  val mtags = new Mtags
  private val sourceJars = new ClasspathLoader(Classpath(Nil))
  override def symbol(path: AbsolutePath, range: s.Range): Option[String] = {
    for {
      document <- getDocument(path)
      occ <- document.occurrences.find(_.range.exists(_.encloses(range)))
    } yield occ.symbol
  }.headOption
  override def definition(symbol: String): Option[SymbolDefinition] = {
    getDefinition(symbol, symbol)
  }
  private def addTextDocuments(
      file: AbsolutePath,
      docs: s.TextDocuments
  ): Unit = {
    docs.documents.foreach { document =>
      document.occurrences.foreach { occ =>
        if (occ.symbol.isLocal) {
          () // Do nothing, compute on demand
        } else {
          if (occ.role.isReference) {
            addReference(occ.symbol, file)
          } else if (occ.role.isDefinition) {
            definitions.put(occ.symbol, file)
          }
        }
      }
    }
  }

  override def addSemanticdb(file: AbsolutePath): Unit = {
    val docs: s.TextDocuments = PathIO.extension(file.toNIO) match {
      case "semanticdb" =>
        Semanticdbs.loadTextDocuments(file)
      case "scala" | "java" =>
        val language = file.toLanguage
        val input = file.toInput
        val document = mtags.index(language, input)
        s.TextDocuments(List(document))
      case _ =>
        s.TextDocuments(Nil)
    }
    if (docs.documents.nonEmpty) {
      addTextDocuments(file, docs)
    }
  }

  override def addSourceFile(source: AbsolutePath): Unit = {
    if (source.toLanguage.isScala) {
      val path = source.toString()
      val text = FileIO.slurp(source, StandardCharsets.UTF_8)
      val input = Input.VirtualFile(path, text)
      val sourceToplevels = mtags.toplevels(input)
      sourceToplevels.foreach { toplevel =>
        if (!isTrivialToplevelSymbol(path, toplevel)) {
          toplevels(toplevel) = source
        }
      }
    }
  }

  override def addSourceJar(jar: AbsolutePath): Unit = {
    sourceJars.addEntry(jar)
    FileIO.withJarFileSystem(jar, create = false) { root =>
      FileIO.listAllFilesRecursively(root).foreach { source =>
        addSourceFile(source)
      }
    }
  }

  /** Returns true if symbol is com/foo/Bar# and path is /com/foo/Bar.scala */
  private def isTrivialToplevelSymbol(path: String, symbol: String): Boolean = {
    val pathBuffer =
      CharBuffer.wrap(path).subSequence(1, path.length - ".scala".length)
    val symbolBuffer =
      CharBuffer.wrap(symbol).subSequence(0, symbol.length - 1)
    pathBuffer.equals(symbolBuffer)
  }

  private def trivialPaths(toplevel: String): List[String] = {
    val noExtension = toplevel.stripSuffix(".").stripSuffix("#")
    List(
      noExtension + ".scala",
      noExtension + ".java"
    )
  }

  final private def loadFromSourceJars(
      paths: List[String]
  ): Option[AbsolutePath] = {
    paths match {
      case Nil =>
        None
      case head :: tail =>
        sourceJars.load(head) match {
          case Some(file) => Some(file)
          case _ => loadFromSourceJars(tail)
        }
    }
  }

  private def getDefinition(
      querySymbol: String,
      symbol: String
  ): Option[SymbolDefinition] = {
    if (!definitions.contains(symbol)) {
      val toplevel = symbol.toplevel
      toplevels.get(toplevel) match {
        case Some(file) =>
          addSemanticdb(file)
        case _ =>
          loadFromSourceJars(trivialPaths(toplevel)).foreach(addSemanticdb)
      }
    }
    if (!definitions.contains(symbol)) {
      DefinitionAlternatives(symbol)
        .flatMap(alternative => getDefinition(querySymbol, alternative))
        .headOption
    } else {
      definitions.get(symbol).map { path =>
        SymbolDefinition(
          querySymbol = querySymbol,
          definitionSymbol = symbol,
          path
        )
      }
    }
  }

  private def getDocument(path: AbsolutePath): Seq[s.TextDocument] = {
    if (path.isFile) Semanticdbs.loadTextDocuments(path).documents
    else Nil
  }
  private def addReference(symbol: String, path: AbsolutePath): Unit = {
    val old = references.getOrElseUpdate(symbol, new mutable.HashSet())
    old.add(path)
  }
}
