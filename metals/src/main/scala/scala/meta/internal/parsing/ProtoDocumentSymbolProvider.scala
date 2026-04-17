package scala.meta.internal.parsing

import java.util

import scala.collection.concurrent.TrieMap
import scala.jdk.OptionConverters._
import scala.util.control.NonFatal

import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser
import scala.meta.internal.proto.tree.Proto
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.{lsp4j => l}

final class ProtoDocumentSymbolProvider(
    buffers: Buffers
) {
  private val lastGoodProto =
    TrieMap.empty[AbsolutePath, Proto.ProtoFile]

  def documentSymbols(path: AbsolutePath): Seq[DocumentSymbol] = {
    val input = path.toInputFromBuffers(buffers)
    val text = input.text

    def parseNow(): Option[Proto.ProtoFile] = {
      try {
        val source = new SourceFile(path.toURI.toString(), text)
        Some(Parser.parse(source))
      } catch {
        case NonFatal(_) => None
      }
    }

    val isOpenInEditor = buffers.get(path).isDefined
    val file =
      parseNow() match {
        case Some(parsed) =>
          if (isOpenInEditor) lastGoodProto.put(path, parsed)
          Some(parsed)
        case None =>
          // Only fallback to the last known-good snapshot while the file is open.
          if (isOpenInEditor) lastGoodProto.get(path) else None
      }

    def range(node: Proto): l.Range =
      Position.Range(input, node.position(), node.endPosition()).toLsp

    def newSymbol(
        name: String,
        kind: SymbolKind,
        decl: Proto,
        detail: String = "",
    ): DocumentSymbol =
      new DocumentSymbol(
        if (name.isEmpty) " " else name,
        kind,
        range(decl),
        range(decl),
        detail,
        new util.ArrayList[DocumentSymbol](),
      )

    def fieldLabel(mod: Proto.FieldModifier): String =
      mod match {
        case Proto.FieldModifier.REQUIRED => "required"
        case Proto.FieldModifier.OPTIONAL => "optional"
        case Proto.FieldModifier.REPEATED => "repeated"
        case _ => ""
      }

    def buildMessage(msg: Proto.MessageDecl): DocumentSymbol = {
      val sym = newSymbol(msg.name().value(), SymbolKind.Class, msg)
      // Preserve order as it appears in the file (best effort).
      msg.fields().asScala.foreach { f =>
        val mod = fieldLabel(f.modifier())
        val label =
          List(mod, f.`type`().fullName()).filter(_.nonEmpty).mkString(" ")
        val detail = s"$label ${f.name().value()} = ${f.number()}"
        sym.getChildren.add(
          newSymbol(f.name().value(), SymbolKind.Field, f, detail)
        )
      }
      msg.mapFields().asScala.foreach { f =>
        val detail =
          s"map<${f.keyType().fullName()}, ${f.valueType().fullName()}> ${f.name().value()} = ${f.number()}"
        sym.getChildren.add(
          newSymbol(f.name().value(), SymbolKind.Field, f, detail)
        )
      }
      msg.oneofs().asScala.foreach { o =>
        val oneofSym = newSymbol(o.name().value(), SymbolKind.Struct, o)
        o.fields().asScala.foreach { f =>
          val mod = fieldLabel(f.modifier())
          val label =
            List(mod, f.`type`().fullName()).filter(_.nonEmpty).mkString(" ")
          val detail = s"$label ${f.name().value()} = ${f.number()}"
          oneofSym.getChildren.add(
            newSymbol(f.name().value(), SymbolKind.Field, f, detail)
          )
        }
        sym.getChildren.add(oneofSym)
      }
      msg.nestedMessages().asScala.foreach { m =>
        sym.getChildren.add(buildMessage(m))
      }
      msg.nestedEnums().asScala.foreach { e =>
        sym.getChildren.add(buildEnum(e))
      }
      sym
    }

    def buildEnum(enum: Proto.EnumDecl): DocumentSymbol = {
      val sym = newSymbol(enum.name().value(), SymbolKind.Enum, enum)
      enum.values().asScala.foreach { v =>
        sym.getChildren.add(
          newSymbol(
            v.name().value(),
            SymbolKind.EnumMember,
            v,
            v.number().toString,
          )
        )
      }
      sym
    }

    def buildService(svc: Proto.ServiceDecl): DocumentSymbol = {
      val sym = newSymbol(svc.name().value(), SymbolKind.Interface, svc)
      svc.rpcs().asScala.foreach { rpc =>
        val in = rpc.inputType().fullName()
        val out = rpc.outputType().fullName()
        val cs = if (rpc.clientStreaming()) "stream " else ""
        val ss = if (rpc.serverStreaming()) "stream " else ""
        val detail =
          s"rpc ${rpc.name().value()}(${cs}${in}) returns (${ss}${out})"
        sym.getChildren.add(
          newSymbol(rpc.name().value(), SymbolKind.Method, rpc, detail)
        )
      }
      sym
    }

    def buildExtend(ext: Proto.ExtendDecl): DocumentSymbol = {
      val sym = newSymbol(
        s"extend ${ext.extendee().fullName()}",
        SymbolKind.Struct,
        ext,
      )
      ext.fields().asScala.foreach { f =>
        val mod = fieldLabel(f.modifier())
        val label =
          List(mod, f.`type`().fullName()).filter(_.nonEmpty).mkString(" ")
        val detail = s"$label ${f.name().value()} = ${f.number()}"
        sym.getChildren.add(
          newSymbol(f.name().value(), SymbolKind.Field, f, detail)
        )
      }
      sym
    }

    file.toSeq.flatMap { f =>
      val decls = f
        .declarations()
        .asScala
        .collect {
          case m: Proto.MessageDecl => buildMessage(m)
          case e: Proto.EnumDecl => buildEnum(e)
          case s: Proto.ServiceDecl => buildService(s)
          case ext: Proto.ExtendDecl => buildExtend(ext)
        }
        .toList

      f.pkg().toScala match {
        case Some(pkg) =>
          val pkgSym = newSymbol(pkg.fullName(), SymbolKind.Package, pkg)
          decls.foreach(pkgSym.getChildren.add)
          List(pkgSym)
        case None =>
          decls
      }
    }
  }
}
