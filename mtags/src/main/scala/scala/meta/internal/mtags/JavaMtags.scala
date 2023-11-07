package scala.meta.internal.mtags

import javax.lang.model.element.Modifier

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.mtags.JavaTokenizer.Token.Word
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.tokenizers.Reporter

import com.sun.source.tree.ClassTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.ModifiersTree
import com.sun.source.tree.PackageTree
import com.sun.source.tree.Tree
import com.sun.source.tree.Tree.Kind.ENUM
import com.sun.source.tree.Tree.Kind.INTERFACE
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreeScanner

object JavaMtags {
  def index(
      input: Input.VirtualFile,
      includeMembers: Boolean
  ): MtagsIndexer =
    new JavaMtags(input, includeMembers)
}

class JavaMtags(virtualFile: Input.VirtualFile, includeMembers: Boolean)
    extends TreeScanner[Unit, ParseTrees]
    with MtagsIndexer {

  private var overloads = new OverloadDisambiguator()
  private val namePositions = new NamePositions(input)

  override def language: Language = Language.JAVA
  override def input: Input.VirtualFile = virtualFile
  override def indexRoot(): Unit = {
    JavaParser.parse(input.text, input.path).foreach { trees =>
      scan(trees.unit, trees)
    }
  }

  override def visitClass(node: ClassTree, trees: ParseTrees): Unit = {
    val owner = currentOwner
    val oldOverloads = overloads
    overloads = new OverloadDisambiguator()
    namePositions.firstNamePosition(node, trees).foreach { position =>
      val (kind, properties) = node.getKind() match {
        case INTERFACE =>
          (SymbolInformation.Kind.INTERFACE, 0)
        case ENUM =>
          (SymbolInformation.Kind.CLASS, SymbolInformation.Property.ENUM.value)
        case _ =>
          (SymbolInformation.Kind.CLASS, 0)
      }
      tpe(
        node.getSimpleName().toString(),
        position,
        kind,
        properties
      )
    }
    super.visitClass(node, trees)
    currentOwner = owner
    overloads = oldOverloads
  }

  override def visitPackage(node: PackageTree, trees: ParseTrees): Unit = {
    namePositions.packageParts(node, trees).foreach {
      case Word(name, position) => pkg(name, position)
    }
    super.visitPackage(node, trees)
  }

  override def visitMethod(node: MethodTree, trees: ParseTrees): Unit = if (
    includeMembers && !isPrivate(node.getModifiers())
  ) {
    namePositions.secondNamePosition(node, trees).foreach { position =>
      withOwner() {
        val name = node.getName().toString
        val disambiguator = overloads.disambiguator(name)
        method(name, disambiguator, position, 0)
      }
    }
  }

  override def visitVariable(node: VariableTree, trees: ParseTrees): Unit = if (
    includeMembers && !isPrivate(node.getModifiers())
  ) {
    namePositions.secondNamePosition(node, trees).foreach { position =>
      withOwner() {
        val name = node.getName().toString()
        term(name, position, SymbolInformation.Kind.FIELD, 0)
      }
    }
  }

  private def isPrivate(modifiers: ModifiersTree) =
    modifiers.getFlags().contains(Modifier.PRIVATE)

}

class NamePositions(input: Input) {
  private val reporter: Reporter = Reporter(input)
  private val reader: CharArrayReader =
    new CharArrayReader(input, dialects.Scala213, reporter)
  private val tokenizer = new JavaTokenizer(reader, input)

  def firstNamePosition(node: Tree, trees: ParseTrees): Option[Position] = {
    tokenizer.moveCursor(trees.getStart(node))
    tokenizer.consumeUntilWord().map(_.pos)
  }

  def secondNamePosition(node: Tree, trees: ParseTrees): Option[Position] = {
    tokenizer.moveCursor(trees.getStart(node))
    val prev = tokenizer.consumeUntilWord()
    tokenizer.consumeUntilWord().orElse(prev).map(_.pos)
  }

  def packageParts(node: Tree, trees: ParseTrees): List[Word] = {
    tokenizer.moveCursor(trees.getStart(node))
    LazyList
      .continually(tokenizer.fetchToken)
      .takeWhile {
        case _: Word => true
        case JavaTokenizer.Token.Dot => true
        case JavaTokenizer.Token.Package => true
        case _ => false
      }
      .collect { case w: Word =>
        w
      }
      .toList
  }
}
