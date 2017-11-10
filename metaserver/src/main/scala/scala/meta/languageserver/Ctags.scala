package scala.meta.languageserver

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipInputStream
import scala.collection.GenSeq
import scala.collection.parallel.mutable.ParArray
import scala.meta.Defn
import scala.meta.Defn
import scala.meta.Name
import scala.meta.Pat
import scala.meta.Pkg
import scala.meta.Source
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.Type
import scala.meta.parsers.ParseException
import scala.meta.transversers.Traverser
import scala.util.control.NonFatal
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import org.langmeta.internal.io.FileIO
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath
import org.langmeta.io.Fragment
import org.langmeta.io.RelativePath
import org.langmeta.semanticdb._

/**
 * Syntactically build a semanticdb index containing only global symbol definition.
 *
 * The purpose of this module is to provide "Go to definition" from
 * project sources to dependency sources without indexing classfiles or
 * requiring dependencies to publish semanticdbs alongside their artifacts.
 *
 * One other use-case for this module is to implement "Workspace symbol provider"
 * without any build-tool or compiler integration. Essentially, ctags.
 */
object Ctags extends LazyLogging {

  /**
   * Build an index from a classpath of -sources.jar
   *
   * @param shouldIndex An optional filter to skip particular relative filenames.
   * @param inParallel If true, use parallel collection to index using all
   *                   available CPU power. If false, uses single-threaded
   *                   collection.
   * @param callback A callback that is called as soon as a document has been
   *                 indexed.
   */
  def index(
      classpath: List[AbsolutePath],
      shouldIndex: RelativePath => Boolean = _ => true,
      inParallel: Boolean = true
  )(callback: Document => Unit): Unit = {
    val fragments = allClasspathFragments(classpath, inParallel)
    fragments.foreach { fragment =>
      try {
        if (shouldIndex(fragment.name)) {
          index(fragment).foreach(callback)
        }
      } catch {
        case _: ParseException => // nothing
        case NonFatal(e) =>
          logger.error(e.getMessage, e)
      }
    }
  }

  def indexJavaSource(filename: String, contents: String): Document = {
    import com.github.javaparser.ast
    val input = Input.VirtualFile(filename, contents)
    def getPosition(t: ast.Node): Position.Range =
      getPositionOption(t).getOrElse(Position.Range(input, -1, -1))
    def getPositionOption(t: ast.Node): Option[Position.Range] =
      for {
        start <- Option(t.getBegin.orElse(null))
        end <- Option(t.getEnd.orElse(null))
      } yield {
        val startOffset = Positions.positionToOffset(
          filename,
          contents,
          start.line - 1,
          start.column - 1
        )
        val endOffset = Positions.positionToOffset(
          filename,
          contents,
          end.line - 1,
          end.column - 1
        )
        Position.Range(input, startOffset, endOffset)
      }

    val cu: CompilationUnit = JavaParser.parse(contents)
    object visitor
        extends VoidVisitorAdapter[Unit]
        with SyntaxIndexer[CompilationUnit] {
      override def visit(
          t: ast.PackageDeclaration,
          ignore: Unit
      ): Unit = {
        val pos = getPosition(t.getName)
        def loop(name: ast.expr.Name): Unit =
          Option(name.getQualifier.orElse(null)) match {
            case None =>
              term(name.getIdentifier, pos, PACKAGE)
            case Some(qual) =>
              loop(qual)
              term(name.getIdentifier, pos, PACKAGE)
          }
        loop(t.getName)
        super.visit(t, ignore)
      }
      override def visit(
          t: ast.body.ClassOrInterfaceDeclaration,
          ignore: Unit
      ): Unit = {
        val name = t.getName.asString()
        val pos = getPosition(t.getName)
        withOwner {
          // TODO(olafur) handle static methods/terms
          if (t.isInterface) tpe(name, pos, TRAIT)
          else tpe(name, pos, CLASS)
          super.visit(t, ignore)
        }
      }
      override def visit(
          t: ast.body.MethodDeclaration,
          ignore: Unit
      ): Unit = {
        term(t.getNameAsString, getPosition(t.getName), DEF)
      }
      override def indexRoot(root: CompilationUnit): Unit = visit(root, ())
    }
    val (names, symbols) = visitor.index(cu)
    Document(
      input = input,
      language = "java",
      names = names,
      messages = Nil,
      symbols = symbols,
      synthetics = Nil
    )
  }

  /** Build a Database for a single source file. */
  def indexScalaSource(filename: String, contents: String): Document = {
    val input = Input.VirtualFile(filename, contents)
    val tree = {
      import scala.meta._
      input.parse[Source].get
    }
    val traverser = new DefinitionTraverser
    val (names, symbols) = traverser.index(tree)
    Document(
      input = input,
      language = "scala212",
      names = names,
      messages = Nil,
      symbols = symbols,
      synthetics = Nil
    )
  }

  def index(fragment: Fragment): Option[Document] = {
    val filename = fragment.uri.toString
    val contents =
      new String(FileIO.readAllBytes(fragment.uri), StandardCharsets.UTF_8)
    logger.trace(s"Indexing $filename with length ${contents.length}")
    PathIO.extension(fragment.name.toNIO) match {
      case "scala" => Some(indexScalaSource(filename, contents))
      case els =>
        logger.warn(s"Unknown file extension ${fragment.syntax}")
        None
    }
  }

  private class DefinitionTraverser extends Traverser with SyntaxIndexer[Tree] {
    override def indexRoot(root: Tree): Unit = apply(root)
    override def apply(tree: Tree): Unit = {
      val old = currentOwner
      val next = tree match {
        case t: Source => Continue
        case t: Template => Continue
        case t: Pkg => pkg(t.ref); Continue
        case t: Pkg.Object => term(t.name, PACKAGEOBJECT); Continue
        case t: Defn.Class => tpe(t.name, CLASS); Continue
        case t: Defn.Trait => tpe(t.name, TRAIT); Continue
        case t: Defn.Object => term(t.name, OBJECT); Continue
        case t: Defn.Def => term(t.name, DEF); Stop
        case Defn.Val(_, Pat.Var(name) :: Nil, _, _) => term(name, DEF); Stop
        case Defn.Var(_, Pat.Var(name) :: Nil, _, _) => term(name, DEF); Stop
        case _ => Stop
      }
      next match {
        case Continue => super.apply(tree)
        case Stop => () // do nothing
      }
      currentOwner = old
    }
  }

  trait SyntaxIndexer[T] {
    def indexRoot(root: T): Unit
    def index(root: T): (List[ResolvedName], List[ResolvedSymbol]) = {
      indexRoot(root)
      names.result() -> symbols.result()
    }
    def withOwner[A](thunk: => A): A = {
      val old = currentOwner
      val result = thunk
      currentOwner = old
      result
    }
    def term(name: String, pos: Position, flags: Long): Unit =
      addSignature(Signature.Term(name), pos, flags)
    def term(name: Term.Name, flags: Long): Unit =
      addSignature(Signature.Term(name.value), name.pos, flags)
    def tpe(name: String, pos: Position, flags: Long): Unit =
      addSignature(Signature.Type(name), pos, flags)
    def tpe(name: Type.Name, flags: Long): Unit =
      addSignature(Signature.Type(name.value), name.pos, flags)
    def pkg(ref: Term): Unit = ref match {
      case Name(name) =>
        currentOwner = symbol(Signature.Term(name))
      case Term.Select(qual, Name(name)) =>
        pkg(qual)
        currentOwner = symbol(Signature.Term(name))
    }
    private val root = Symbol("_root_.")
    sealed abstract class Next
    case object Stop extends Next
    case object Continue extends Next
    private val names = List.newBuilder[ResolvedName]
    private val symbols = List.newBuilder[ResolvedSymbol]
    var currentOwner: _root_.scala.meta.Symbol = root
    private def addSignature(
        signature: Signature,
        definition: Position,
        flags: Long
    ): Unit = {
      currentOwner = symbol(signature)
      names += ResolvedName(
        definition,
        currentOwner,
        isDefinition = true
      )
      symbols += ResolvedSymbol(
        currentOwner,
        Denotation(flags, signature.name, "", Nil)
      )
    }
    private def symbol(signature: Signature): Symbol =
      Symbol.Global(currentOwner, signature)
  }

  private def isScala(path: String): Boolean = path.endsWith(".scala")
  private def isScala(path: Path): Boolean = PathIO.extension(path) == "scala"

  /** Returns all *.scala fragments to index from this classpath
   *
   * This implementation is copy-pasted from scala.meta.Classpath.deep with
   * the following differences:
   *
   * - We build a parallel array
   * - We log errors instead of silently ignoring them
   * - We filter out non-scala sources
   */
  private def allClasspathFragments(
      classpath: List[AbsolutePath],
      inParallel: Boolean
  ): GenSeq[Fragment] = {
    var buf =
      if (inParallel) ParArray.newBuilder[Fragment]
      else List.newBuilder[Fragment]
    classpath.foreach { base =>
      def exploreJar(base: AbsolutePath): Unit = {
        val stream = Files.newInputStream(base.toNIO)
        try {
          val zip = new ZipInputStream(stream)
          var entry = zip.getNextEntry
          while (entry != null) {
            if (!entry.getName.endsWith("/") && isScala(entry.getName)) {
              val name = RelativePath(entry.getName.stripPrefix("/"))
              buf += Fragment(base, name)
            }
            entry = zip.getNextEntry
          }
        } catch {
          case ex: IOException =>
            logger.error(ex.getMessage, ex)
        } finally {
          stream.close()
        }
      }
      if (base.isDirectory) {
        Files.walkFileTree(
          base.toNIO,
          new SimpleFileVisitor[Path] {
            override def visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult = {
              if (isScala(file)) {
                buf += Fragment(base, RelativePath(base.toNIO.relativize(file)))
              }
              FileVisitResult.CONTINUE
            }
          }
        )
      } else if (base.isFile) {
        if (base.toString.endsWith(".jar")) {
          exploreJar(base)
        } else {
          sys.error(
            s"Obtained non-jar file $base. Expected directory or *.jar file."
          )
        }
      } else {
        logger.info(s"Skipping $base")
        // Skip
      }
    }
    buf.result()
  }
}
