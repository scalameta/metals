package tests.pc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerOffsetParams

import tests.pc.BaseJavaPCSuite

abstract class BaseJavaSignatureHelpSuite extends BaseJavaPCSuite {

  def qux2 = 42

  def check(
      options: munit.TestOptions,
      original: String,
      expected: String,
  )(implicit loc: munit.Location): Unit = {
    test(options) {
      val filename = "SignatureHelp.java"
      val codeOriginal = s"package ${packageName(options.name)};\n${original}"
      val (code, so) = params(codeOriginal, filename)
      val uri = Paths.get(filename).toUri()
      val help = presentationCompiler
        .signatureHelp(CompilerOffsetParams(uri, code, so))
        .get()
      val obtained = help
        .getSignatures()
        .asScala
        .zipWithIndex
        .map { case (signature, signatureIndex) =>
          val documentation = if (signature.getDocumentation().isLeft()) {
            signature.getDocumentation().getLeft()
          } else {
            signature.getDocumentation().getRight().getValue()
          }
          val sig = new StringBuilder()
          var startCharacter = 0
          var endCharacter = 0
          val isActiveSignature = signatureIndex == help.getActiveSignature()
          sig.append(if (isActiveSignature) "=> " else "   ")
          sig.append(signature.getLabel())
          var i = -1
          signature.getParameters().asScala.zipWithIndex.foreach {
            case (parameterInfo, index) =>
              if (parameterInfo.getLabel().isRight()) {
                throw new RuntimeException(
                  "Parameter info should have a label"
                )
              }
              val info = parameterInfo.getLabel().getLeft()
              i = signature.getLabel().indexOf(info, i)
              if (i == -1) {
                throw new RuntimeException(
                  s"Parameter info '${info}' not found in signature '${signature.getLabel()}'"
                )
              }
              val isActive =
                isActiveSignature && index == help.getActiveParameter()
              if (isActive) {
                startCharacter = i
                endCharacter = i + info.length()
              }
          }
          if (isActiveSignature && startCharacter != endCharacter) {
            sig.append("\n   ")
            sig.append(" ".repeat(startCharacter))
            sig.append("^".repeat(endCharacter - startCharacter))
          }
          Option(documentation).foreach { documentation =>
            if (!documentation.isBlank()) {
              sig.append("\n   ")
              sig.append(documentation)
            }
          }
          sig.toString()
        }
        .mkString("\n")
      assertNoDiff(obtained, expected)
    }

  }
}
