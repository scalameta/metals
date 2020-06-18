package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.TextDocumentPositionParams

final class DocumentHighlightProvider(
    definitionProvider: DefinitionProvider,
    semanticdbs: Semanticdbs
) {

  def documentHighlight(
      params: TextDocumentPositionParams
  ): java.util.List[DocumentHighlight] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val result = semanticdbs.textDocument(source)

    val highlights = for {
      doc <- result.documentIncludingStale.toList
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params.getPosition,
        doc
      )
      occ <- positionOccurrence.occurrence.toList
      alternatives = findAllAlternatives(doc, occ)
      curr <- doc.occurrences
      if curr.symbol == occ.symbol || alternatives(curr.symbol)
      range <- curr.range
      revised <- positionOccurrence.distance.toRevised(range.toLSP)
      kind =
        if (curr.role.isDefinition) {
          DocumentHighlightKind.Write
        } else {
          DocumentHighlightKind.Read
        }
    } yield new DocumentHighlight(revised, kind)
    highlights.asJava
  }

  private def findAllAlternatives(
      doc: TextDocument,
      occ: SymbolOccurrence
  ): Set[String] = {

    val symbolInfo = doc.symbols.find(_.symbol == occ.symbol)
    symbolInfo match {
      case Some(info) =>
        if (info.isClass) Set(info.symbol.dropRight(1) + ".")
        else if (info.isObject) Set(info.symbol.dropRight(1) + "#")
        else if (
          info.isParameter && (info.symbol.contains("apply") ||
          info.symbol.contains("copy"))
        ) {
          parameterAlternatives(info)
        } else if (info.isMethod) {
          methodAlternatives(info)
        } else {
          Set.empty
        }
      case None => Set.empty
    }
  }

  private def methodAlternatives(info: SymbolInformation): Set[String] = {

    def isInObject(desc: Descriptor) =
      desc match {
        case Descriptor.Term(value) => true
        case _ => false
      }

    val setterSuffix = "_="
    Symbol(info.symbol) match {
      case GlobalSymbol(
            GlobalSymbol(owner, descriptor),
            Descriptor.Method(setter, disambiguator)
          ) =>
        generateAlternativeSymbols(
          setter.stripSuffix(setterSuffix),
          descriptor.value,
          owner.value,
          isInObject(descriptor)
        )
      case GlobalSymbol(
            GlobalSymbol(owner, descriptor),
            Descriptor.Term(name)
          ) =>
        generateAlternativeSymbols(
          name,
          descriptor.value,
          owner.value,
          isInObject(descriptor)
        )
      case _ => Set.empty
    }
  }

  private def parameterAlternatives(info: SymbolInformation): Set[String] = {
    val copyOrApply = Set("apply", "copy")
    Symbol(info.symbol) match {
      case GlobalSymbol(
            GlobalSymbol(
              GlobalSymbol(owner, descriptor),
              Descriptor.Method(name, disambiguator)
            ),
            desc
          ) if copyOrApply(name) =>
        generateAlternativeSymbols(
          desc.value,
          descriptor.value,
          owner.value,
          areParamsInObject = false
        )

      case _ =>
        Set.empty
    }
  }

  private def generateAlternativeSymbols(
      paramName: String,
      className: String,
      packageName: String,
      areParamsInObject: Boolean
  ): Set[String] = {
    val setterSuffix = "_="
    val paramsDescriptor =
      if (areParamsInObject) Descriptor.Term(className)
      else Descriptor.Type(className)
    Set(
      Symbols.Global(
        Symbols.Global(packageName, Descriptor.Type(className)),
        Descriptor.Term(paramName)
      ),
      Symbols.Global(
        Symbols.Global(
          Symbols.Global(packageName, Descriptor.Type(className)),
          Descriptor.Method("copy", "()")
        ),
        Descriptor.Parameter(paramName)
      ),
      Symbols.Global(
        Symbols.Global(
          Symbols.Global(packageName, Descriptor.Term(className)),
          Descriptor.Method("apply", "()")
        ),
        Descriptor.Parameter(paramName)
      ),
      Symbols.Global(
        Symbols.Global(packageName, paramsDescriptor),
        Descriptor.Method(paramName, "()")
      ),
      Symbols.Global(
        Symbols.Global(packageName, paramsDescriptor),
        Descriptor.Method(paramName + setterSuffix, "()")
      )
    )
  }
}
