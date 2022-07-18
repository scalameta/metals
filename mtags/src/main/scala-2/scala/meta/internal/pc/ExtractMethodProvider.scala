package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class ExtractMethodProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams
) {
  import compiler._
  def extractMethod: List[l.TextEdit] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )
    val pos = unit.position(params.offset())
    val typedTree = typedTreeAt(pos)
    pprint.pprintln(typedTree)

    // def isBlockOrTemplate(t: Tree) =
    //   t match {
    //     case _: Template => true
    //     case _: Block => true
    //     case _ => false
    //   }

    // def defs(t: Tree): Option[Seq[Tree]] =
    //   t match {
    //     case template: Template =>
    //       Some(template.body.filter(_.isDef))
    //     case block: Block =>
    //       Some(block.stats.filter(_.isDef))
    //     case _ => None
    //   }
    // val printer = MetalsPrinter.standard(
    //   indexedCtx,
    //   symbolSearch,
    //   includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater
    // )

    // def localVariables(ts: Seq[Tree]): Seq[(TermName, String)] =
    //   ts.flatMap(
    //     _ match {
    //       case vl @ ValDef(_, sym, tpt, _) => Seq((sym, printer.tpe(tpt.tpe)))
    //       // case dl @ DefDef(name, _, tpt, rhs) =>
    //       //   val paramss = localVariablesInDefDef(dl)
    //       //   val paramTypes = paramss.map(_.map(_._2)).map(ls => s"(${ls.mkString(", ")})").mkString
    //       //  Seq((name, paramTypes + " => " + printer.tpe(tpt.tpe))) ++ paramss.flatten
    //       case b: Block => localVariables(b.stats)
    //       case t: Template => /*localVariablesInDefDef(t.constr).flatten ++ */
    //         localVariables(t.body)
    //       case _ => Nil
    //     }
    //   )

    // def namesInVal(t: Tree): Set[TermName] =
    //   t match {
    //     case Apply(fun, args) =>
    //       namesInVal(fun) ++ args.flatMap(namesInVal(_)).toSet
    //     case TypeApply(fun, args) =>
    //       namesInVal(fun) ++ args.flatMap(namesInVal(_)).toSet
    //     case Select(qualifier, name) => namesInVal(qualifier) + name.toTermName
    //     case Ident(name) => Set(name.toTermName)
    //     case _ => Set()
    //   }

    // def getName(t: Tree): Option[TermName] =
    //   t match {
    //     case Ident(name) => Some(name.toTermName)
    //     case _ => None
    //   }
    // def nameDef(newName: TermName, t: Tree): Boolean =
    //   t match {
    //     case dl @ DefDef(_, name, _, _, _, _) if name == newName => true
    //     case _ => false
    //   }
    // def extracted(t: Tree): Option[Tree] = {
    //   t match {
    //     case d @ DefDef(_, name, _, _, _, rhs) => Some(rhs) // problem z typem
    //     case _ => None
    //   }
    // }
    
    // val edits = 
    //   for {
    //   ident <- Some(typedTree)
    //   name <- getName(ident)
    //   stat <- path.find(isBlockOrTemplate(_))
    //   allDefs <- defs(stat)
    //   nameDef <- allDefs.find(nameDef(name, _))
    //   apply <- extracted(nameDef)
      
    //   }yield {
    //     val namesInAppl = namesInVal(apply)
    //     val locals = localVariables(path).reverse.toMap
    //     pprint.pprintln("in appls")
    //     pprint.pprintln(namesInAppl)

    //     pprint.pprintln("locals")

    //     pprint.pprintln(locals)
    //     val withType =
    //       locals.filter((key, tp) => namesInAppl.contains(key))
    //     val typs = withType
    //       .map((k, v) => s"$k: $v")
    //       .mkString(", ")
    //     val tps = typs
    //     val applParams = withType.keys.mkString(", ")
    //     pprint.pprintln("Typy")
    //     pprint.pprintln(tps)
    //     val nameShift = name.toString.length() + 1
    //     val defSpan = nameDef.startPos.span.shift(nameShift + 4)
    //     val defPos = new SourcePosition(source, defSpan).toLSP
    //     val applSpan = pos.startPos.span.shift(nameShift)
    //     val applPos = new SourcePosition(source, applSpan).toLSP

    //     List(
    //       new l.TextEdit(
    //         defPos,
    //         typs
    //       ),
    //       new l.TextEdit(
    //         applPos,
    //         applParams
    //       )
    //     )

    //   }

    Nil
  }
}
