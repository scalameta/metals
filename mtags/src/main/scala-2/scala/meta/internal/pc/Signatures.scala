package scala.meta.internal.pc

import scala.collection.Seq
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc
import scala.meta.pc.SymbolDocumentation

import org.eclipse.{lsp4j => l}

trait Signatures { this: MetalsGlobal =>

  case class ShortName(
      name: Name,
      symbol: Symbol
  ) {
    def isRename: Boolean = symbol.name != name
    def asImport: String = {
      val ident = Identifier(name)
      if (isRename) s"${Identifier(symbol.name)} => ${ident}"
      else ident
    }
    def owner: Symbol = symbol.owner
  }
  object ShortName {
    def apply(sym: Symbol): ShortName =
      ShortName(sym.name, sym)
  }

  object ShortenedNames {

    /**
     * Pretty-prints a type at a given position/context with optional auto-imports.
     *
     * @param tpe the type to pretty-print.
     * @param pos the position where the type will be inserted.
     * @param scope the scope at the given position to know what names resolve to which symbols.
     * @param importPosition the position where to place auto-imports.
     */
    def synthesize(
        tpe: Type,
        pos: Position,
        scope: Context,
        importPosition: AutoImportPosition
    ): (String, List[l.TextEdit]) = {
      val history = new ShortenedNames(
        lookupSymbol = name => {
          val companion =
            if (name.isTypeName) name.toTermName
            else name.toTypeName
          scope.lookupSymbol(name, _ => true) ::
            scope.lookupSymbol(companion, _ => true) :: Nil
        },
        renames = renamedSymbols(scope),
        config = renameConfig
      )
      val tpeString = shortType(tpe, history).toString()
      val edits = history.autoImports(
        pos,
        scope,
        importPosition.offset,
        importPosition.indent,
        importPosition.padTop
      )
      (tpeString, edits)
    }

    def synthesize(
        sym: Symbol,
        pos: Position,
        scope: Context,
        importPosition: AutoImportPosition
    ): (String, List[l.TextEdit]) = {
      if (scope.symbolIsInScope(sym)) (Identifier(sym.name), Nil)
      else if (!scope.nameIsInScope(sym.name)) {
        val startPos = pos.withPoint(importPosition.offset).focus
        val indent = " " * importPosition.indent
        val edit = new l.TextEdit(
          startPos.toLSP,
          s"${indent}import ${sym.fullNameSyntax}\n"
        )
        (Identifier(sym.name), edit :: Nil)
      } else {
        // HACK(olafur): we're using a type pretty-printer to pretty-print term objects here.
        // A better solution would be to implement a proper term pretty-printer but that would require more work.
        val (short, edit) = synthesize(
          TypeRef(
            ThisType(sym.owner),
            sym,
            Nil
          ),
          pos,
          scope,
          importPosition
        )
        if (sym.hasModuleFlag && !sym.isJavaDefined) {
          (short.stripSuffix(".type"), edit)
        } else {
          (short, edit)
        }
      }
    }
  }

  class ShortenedNames(
      val history: mutable.Map[Name, ShortName] = mutable.Map.empty,
      val lookupSymbol: Name => List[NameLookup] = _ => Nil,
      val config: collection.Map[Symbol, Name] = Map.empty,
      val renames: collection.Map[Symbol, Name] = Map.empty,
      val owners: collection.Set[Symbol] = Set.empty
  ) {
    def this(context: Context) =
      this(lookupSymbol = { name =>
        context.lookupSymbol(name, _ => true) :: Nil
      })

    def fullname(sym: Symbol): String = {
      if (topSymbolResolves(sym)) sym.fullNameSyntax
      else s"_root_.${sym.fullNameSyntax}"
    }

    def topSymbolResolves(sym: Symbol): Boolean = {
      // Returns the package `a` for the symbol `_root_.a.b.c`
      @annotation.tailrec
      def topPackage(s: Symbol): Symbol = {
        val owner = s.owner
        if (
          s.isRoot || s.isRootPackage || s == NoSymbol || s.owner.isEffectiveRoot || s == owner
        ) {
          s
        } else {
          topPackage(owner)
        }
      }
      val top = topPackage(sym)
      nameResolvesToSymbol(top.name.toTermName, top)
    }

    def nameResolvesToSymbol(sym: Symbol): Boolean = {
      nameResolvesToSymbol(sym.name, sym)
    }
    def nameResolvesToSymbol(name: Name, sym: Symbol): Boolean = {
      lookupSymbol(name) match {
        case Nil => true
        case lookup => lookup.exists(_.symbol.isKindaTheSameAs(sym))
      }
    }

    def tryShortenName(short: ShortName): Boolean = {
      val ShortName(name, sym) = short
      history.get(name) match {
        case Some(ShortName(_, other)) =>
          if (other.isKindaTheSameAs(sym)) true
          else false
        case _ =>
          val isOk = lookupSymbol(name).filter(_ != LookupNotFound) match {
            case Nil => true
            case lookup =>
              lookup.exists(_.symbol.isKindaTheSameAs(sym))
          }
          if (isOk) {
            history(name) = short
            true
          } else {
            false // conflict, do not shorten name.
          }
      }
    }
    def tryShortenName(name: Option[ShortName]): Boolean =
      name match {
        case Some(short) =>
          tryShortenName(short)
        case _ =>
          false
      }

    // Returns the list of text edits to insert imports for symbols that got shortened.
    def autoImports(
        pos: Position,
        context: Context,
        lineStart: Int,
        inferIndent: => Int,
        padTop: Boolean
    ): List[l.TextEdit] = {
      val toImport = mutable.Map.empty[Symbol, List[ShortName]]
      val isRootSymbol = Set[Symbol](
        rootMirror.RootClass,
        rootMirror.RootPackage
      )
      for {
        (name, sym) <- history.iterator
        owner = sym.owner
        if !isRootSymbol(owner)
        if !context.lookupSymbol(name, _ => true).isSuccess
      } {
        toImport(owner) = sym :: toImport.getOrElse(owner, Nil)
      }
      if (toImport.nonEmpty) {
        val indent = " " * inferIndent
        val topPadding =
          if (padTop) "\n"
          else ""
        val scope = new ShortenedNames(context)
        val formatted = toImport.toSeq
          .sortBy {
            case (owner, _) => owner.fullName
          }
          .map {
            case (owner, names) =>
              val isGroup =
                names.lengthCompare(1) > 0 ||
                  names.exists(_.isRename)
              val importNames = names.map(_.asImport).sorted
              val name =
                if (isGroup) importNames.mkString("{", ", ", "}")
                else importNames.mkString
              s"${indent}import ${scope.fullname(owner)}.${name}"
          }
          .mkString(topPadding, "\n", "\n")
        val startPos = pos.withPoint(lineStart).focus
        new l.TextEdit(startPos.toLSP, formatted) :: Nil
      } else {
        Nil
      }
    }
  }

  class SignaturePrinter(
      gsym: Symbol,
      shortenedNames: ShortenedNames,
      gtpe: Type,
      includeDocs: Boolean,
      includeDefaultParam: Boolean = true,
      printLongType: Boolean = true
  ) {
    private val info: Option[SymbolDocumentation] =
      if (includeDocs) {
        symbolDocumentation(gsym)
      } else {
        None
      }
    private val infoParamsA: Seq[pc.SymbolDocumentation] = info match {
      case Some(value) =>
        value.typeParameters().asScala ++
          value.parameters().asScala
      case None =>
        IndexedSeq.empty
    }
    private val infoParams =
      infoParamsA.lift
    private val returnType =
      printType(shortType(gtpe.finalResultType, shortenedNames))

    def printType(tpe: Type): String =
      if (printLongType) tpe.toLongString
      else tpe.toString()
    def methodDocstring: String = {
      if (isDocs) info.fold("")(_.docstring())
      else ""
    }
    def isTypeParameters: Boolean = gtpe.typeParams.nonEmpty
    def implicitParams: Option[List[Symbol]] =
      gtpe.paramss.lastOption.filter(_.headOption.exists(_.isImplicit))
    private val implicitEvidenceTermParams =
      mutable.Set.empty[Symbol]
    val implicitEvidencesByTypeParam
        : collection.Map[Symbol, ListBuffer[String]] = {
      val result = mutable.Map.empty[Symbol, ListBuffer[String]]
      for {
        param <- implicitParams.getOrElse(Nil).iterator
        if param.name.startsWith(termNames.EVIDENCE_PARAM_PREFIX)
        TypeRef(
          _,
          sym,
          TypeRef(NoPrefix, tparam, Nil) :: Nil
        ) <- List(param.info)
      } {
        implicitEvidenceTermParams += param
        val buf = result.getOrElseUpdate(tparam, ListBuffer.empty)
        buf += sym.name.toString
      }
      result
    }
    def isImplicit: Boolean = implicitParams.isDefined
    def mparamss: List[List[Symbol]] =
      gtpe.typeParams match {
        case Nil => gtpe.paramss
        case tparams => tparams :: gtpe.paramss
      }
    def defaultMethodSignature(name: String = ""): String = {
      var i = 0
      val paramss = gtpe.typeParams match {
        case Nil => gtpe.paramss
        case tparams => tparams :: gtpe.paramss
      }
      val params = paramss.iterator.flatMap { params =>
        val labels = params.flatMap { param =>
          if (implicitEvidenceTermParams.contains(param)) {
            Nil
          } else {
            val result = paramLabel(param, i)
            i += 1
            result :: Nil
          }
        }
        if (labels.isEmpty && params.nonEmpty) Nil
        else labels.iterator :: Nil
      }
      methodSignature(params, name)
    }

    def methodSignature(
        paramLabels: Iterator[Iterator[String]],
        name: String = gsym.nameString
    ): String = {
      paramLabels
        .zip(mparamss.iterator)
        .map {
          case (params, syms) =>
            paramsKind(syms) match {
              case Params.TypeParameterKind =>
                params.mkString("[", ", ", "]")
              case Params.NormalKind =>
                params.mkString("(", ", ", ")")
              case Params.ImplicitKind =>
                params.mkString("(implicit ", ", ", ")")
            }
        }
        .mkString(name, "", s": ${returnType}")
    }
    def paramsKind(syms: List[Symbol]): Params.Kind = {
      syms match {
        case head :: _ =>
          if (head.isType) Params.TypeParameterKind
          else if (head.isImplicit) Params.ImplicitKind
          else Params.NormalKind
        case Nil => Params.NormalKind
      }
    }
    def paramDocstring(paramIndex: Int): String = {
      if (isDocs) infoParams(paramIndex).fold("")(_.docstring())
      else ""
    }
    def paramLabel(param: Symbol, index: Int): String = {
      val paramTypeString = printType(shortType(param.info, shortenedNames))
      val name = infoParams(index) match {
        case Some(value) if param.name.startsWith("x$") =>
          value.displayName()
        case _ => param.nameString
      }
      if (param.isTypeParameter) {
        val contextBounds =
          implicitEvidencesByTypeParam.getOrElse(param, Nil) match {
            case Nil => ""
            case head :: Nil => s":$head"
            case many => many.mkString(": ", ": ", "")
          }
        s"$name$paramTypeString$contextBounds"
      } else {
        val default =
          if (includeDefaultParam && param.isParamWithDefault) {
            val defaultValue = infoParams(index).map(_.defaultValue()) match {
              case Some(value) if !value.isEmpty => value
              case _ => "{}"
            }
            s" = $defaultValue"
          } else {
            ""
          }
        s"$name: ${paramTypeString}$default"
      }
    }
  }
}
