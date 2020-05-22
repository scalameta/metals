package tests

import java.nio.file.Paths

import scala.meta.XtensionSyntax
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams

import munit.Location
import munit.TestOptions

abstract class BaseSignatureHelpSuite extends BasePCSuite {
  def checkDoc(
      name: String,
      code: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    check(name, code, expected, includeDocs = true, compat = compat)
  }
  def check(
      name: TestOptions,
      original: String,
      expected: String,
      includeDocs: Boolean = false,
      compat: Map[String, String] = Map.empty,
      stableOrder: Boolean = true
  )(implicit loc: Location): Unit = {
    test(name) {
      val pkg = scala.meta.Term.Name(name.name).syntax
      val (code, offset) = params(s"package $pkg\n" + original)
      val result =
        presentationCompiler
          .signatureHelp(
            CompilerOffsetParams(Paths.get("A.scala").toUri(), code, offset)
          )
          .get()
      val out = new StringBuilder()
      if (result != null) {
        result.getSignatures.asScala.zipWithIndex.foreach {
          case (signature, i) =>
            if (includeDocs) {
              val sdoc = doc(signature.getDocumentation)
              if (sdoc.nonEmpty) {
                out.append(sdoc).append("\n")
              }
            }
            out
              .append(signature.getLabel)
              .append("\n")
            if (
              result.getActiveSignature == i && result.getActiveParameter != null && signature.getParameters
                .size() > 0
            ) {
              val param = signature.getParameters.get(result.getActiveParameter)
              val column = signature.getLabel.indexOf(param.getLabel.getLeft())
              if (column < 0) {
                fail(s"""invalid parameter label
                        |  param.label    : ${param.getLabel}
                        |  signature.label: ${signature.getLabel}
                        |""".stripMargin)
              }
              val indent = " " * column
              out
                .append(indent)
                .append("^" * param.getLabel.getLeft().length)
                .append("\n")
              signature.getParameters.asScala.foreach { param =>
                val pdoc = doc(param.getDocumentation)
                  .stripPrefix("```scala\n")
                  .stripSuffix("\n```")
                  .replace("\n```\n", " ")
                if (includeDocs && pdoc.nonEmpty) {
                  out
                    .append("  @param ")
                    .append(param.getLabel.getLeft().replaceFirst("[ :].*", ""))
                    .append(" ")
                    .append(pdoc)
                    .append("\n")
                }
              }
            }
        }
      }
      assertNoDiff(
        sortLines(stableOrder, out.toString()),
        sortLines(
          stableOrder,
          getExpected(expected, compat, scalaVersion)
        )
      )
    }
  }

  override val compatProcess: Map[String, String => String] = Map(
    "2.13" -> { s =>
      s.replace("valueOf(obj: Any)", "valueOf(obj: Object)")
        .replace("Map[A, B]: Map", "Map[K, V]: Map")
    }
  )
}
