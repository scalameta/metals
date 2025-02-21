package scala.meta.internal.pc.completions

import scala.collection.immutable.Nil

import scala.meta.internal.pc.Identifier
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait ArgCompletions { this: MetalsGlobal =>

  case class ArgCompletion(
      ident: Ident,
      apply: Apply,
      pos: Position,
      text: String,
      completions: CompletionResult
  ) extends CompletionPosition { self =>
    val editRange: l.Range =
      pos.withStart(ident.pos.start).withEnd(pos.start).toLsp
    val funPos = apply.fun.pos
    val method: Tree = typedTreeAt(funPos) match {
      // Functions calls with default arguments expand into this form
      case Apply(
            Block(defParams, app @ (_: Apply | _: Select | _: TypeApply)),
            _
          ) if defParams.forall(p => p.isInstanceOf[ValDef]) =>
        app
      case New(c) => c
      case t => t
    }
    val methodSym = method.symbol
    lazy val methodsParams: List[MethodParams] = {
      if (methodSym.isModule) getParamsFromObject(methodSym)
      else if (
        methodSym.isMethod && methodSym.name == nme.apply && methodSym.owner.isModuleClass
      ) getParamsFromObject(methodSym.owner)
      else if (methodSym.isClass)
        List(MethodParams(methodSym.constrParamAccessors))
      else if (method.tpe == null)
        method match {
          case Ident(name) =>
            metalsScopeMembers(funPos)
              .flatMap {
                case m: Member
                    if m.symNameDropLocal == name && m.sym != NoSymbol =>
                  for {
                    params <- m.sym.paramss.headOption
                    if checkIfArgsMatch(params)
                  } yield MethodParams(params)
                case _ => None
              }
          case _ => Nil
        }
      else {
        method.tpe match {
          case OverloadedType(_, alts) =>
            alts.flatMap(_.info.paramss.headOption).map(MethodParams(_))
          case _ =>
            val params =
              method.tpe.paramss.headOption.getOrElse(methodSym.paramss.flatten)
            List(MethodParams(params))
        }
      }
    }

    def checkIfArgsMatch(possibleMatch: List[Symbol]): Boolean = {
      apply.args.length <= possibleMatch.length &&
      !apply.args.zipWithIndex.exists {
        // the identifier we want to generate completions for
        //            v
        // m(arg1 = 3, wri@@)
        case (Ident(name), _) if name.decodedName.endsWith(CURSOR) => false
        case (AssignOrNamedArg(Ident(name), rhs), _) =>
          !possibleMatch.exists { param =>
            name.decodedName == param.name.decodedName &&
            (rhs.tpe == null || rhs.tpe <:< param.tpe)
          }
        case (rhs, i) =>
          rhs.tpe != null && !(rhs.tpe <:< possibleMatch(i).tpe)
      }
    }

    val prefix: String = ident.name.toString.stripSuffix(CURSOR)
    lazy val allParams: List[Symbol] = methodsParams.flatMap(_.allParams)
    lazy val params: List[Symbol] =
      allParams
        .filter(param => param.name.startsWith(prefix))
        .foldLeft(List.empty[Symbol])((acc, curr) =>
          if (acc.exists(el => el.name == curr.name && el.tpe == curr.tpe)) acc
          else curr :: acc
        )
        .reverse
    lazy val isParamName: Set[String] = params.iterator
      .map(_.name)
      .map(_.toString().trim())
      .toSet

    def isName(m: Member): Boolean =
      isParamName(m.sym.nameString.trim())

    override def compare(o1: Member, o2: Member): Int = {
      val byName = -java.lang.Boolean.compare(isName(o1), isName(o2))
      if (byName != 0) byName
      else {
        java.lang.Boolean.compare(
          o1.isInstanceOf[NamedArgMember],
          o2.isInstanceOf[NamedArgMember]
        )
      }
    }

    override def isPrioritized(member: Member): Boolean = {
      member.isInstanceOf[NamedArgMember] ||
      isParamName(member.sym.name.toString().trim())
    }

    private def matchingTypesInScope(
        paramType: Type
    ): List[String] = {

      def notNothingOrNull(mem: ScopeMember): Boolean = {
        !(mem.sym.tpe =:= definitions.NothingTpe || mem.sym.tpe =:= definitions.NullTpe)
      }

      completions match {
        case members: CompletionResult.ScopeMembers
            if paramType != definitions.AnyTpe =>
          members
            .matchingResults(_ => _ => true)
            .collect {
              case mem
                  if mem.sym.tpe <:< paramType && notNothingOrNull(
                    mem
                  ) && mem.sym.isTerm =>
                mem.sym.name.toString().trim()
            }
            // None and Nil are always in scope
            .filter(name => name != "Nil" && name != "None")
        case _ =>
          Nil
      }
    }

    private def findDefaultValue(param: Symbol): String = {
      val matchingType = matchingTypesInScope(param.tpe)
      if (matchingType.size == 1) {
        s":${matchingType.head}"
      } else if (matchingType.size > 1) {
        s"|???,${matchingType.mkString(",")}|"
      } else {
        ":???"
      }
    }

    private def fillAllFields(): List[TextEditMember] = {
      val suffix = "autofill"
      val shouldShow =
        allParams.exists(param => param.name.startsWith(prefix))
      val isExplicitlyCalled = suffix.startsWith(prefix)
      val hasParamsToFill = allParams.count(!_.hasDefault) > 1
      if (
        methodsParams.length == 1 && (shouldShow || isExplicitlyCalled) && hasParamsToFill && clientSupportsSnippets
      ) {
        val editText = allParams.zipWithIndex
          .collect {
            case (param, index) if !param.hasDefault => {
              s"${Identifier.backtickWrap(param.name).replace("$", "$$")} = $${${index + 1}${findDefaultValue(param)}}"
            }
          }
          .mkString(", ")
        val edit = new l.TextEdit(editRange, editText)
        List(
          new TextEditMember(
            filterText = s"$prefix-$suffix",
            edit = edit,
            methodSym,
            label = Some("Autofill with default values")
          )
        )
      } else {
        List.empty
      }
    }

    private def findPossibleDefaults(): List[TextEditMember] = {
      params.flatMap { param =>
        val allMembers = matchingTypesInScope(param.tpe)
        allMembers.map { memberName =>
          val editText =
            Identifier.backtickWrap(param.name) + " = " + memberName
          val edit = new l.TextEdit(editRange, editText)
          new TextEditMember(
            filterText = param.name.toString(),
            edit = edit,
            completionsSymbol(s"$param=$memberName"),
            label = Some(editText),
            detail = Some(" : " + param.tpe)
          )
        }
      }
    }

    private def getParamsFromObject(objectSym: Symbol): List[MethodParams] = {
      objectSym.info.members.flatMap {
        case m if m.name == nme.apply =>
          for {
            params <- m.paramss.headOption
            if (checkIfArgsMatch(params))
          } yield MethodParams(params)
        case _ => None
      }.toList
    }

    override def contribute: List[Member] = {
      if (methodSym == null) Nil
      else
        params.distinct.map(param =>
          new NamedArgMember(param)
        ) ::: findPossibleDefaults() ::: fillAllFields()
    }

    case class MethodParams(params: List[Symbol], isNamed: Set[Name]) {
      def allParams: List[Symbol] =
        params.filterNot(param => isNamed(param.name) || param.isSynthetic)
    }

    object MethodParams {
      def apply(baseParams: List[Symbol]): MethodParams = {
        val isNamed =
          self.apply.args.iterator
            .filterNot(_ == ident)
            .zip(baseParams.iterator)
            .map {
              case (AssignOrNamedArg(Ident(name), _), _) => name
              case (_, param) => param.name
            }
            .toSet
        MethodParams(baseParams, isNamed)
      }
    }
  }
}
