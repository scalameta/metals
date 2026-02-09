package tests.turbinec

import munit.FunSuite
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class TurbineConformanceCliSuite extends FunSuite {
  import TurbineConformanceCli.AbiScope

  test("parse-abi-scope") {
    assertEquals(TurbineConformanceCli.parseAbiScope("java"), Right(AbiScope.Java))
    assertEquals(TurbineConformanceCli.parseAbiScope("java-used"), Right(AbiScope.JavaUsed))
    assertEquals(TurbineConformanceCli.parseAbiScope("FULL"), Right(AbiScope.Full))
    val invalid = TurbineConformanceCli.parseAbiScope("invalid")
    assert(invalid.isLeft)
  }

  test("classify-baseline-only-java-scope") {
    val baselineClasses = Map(
      "foo/FromSkipped" -> classBytes(
        internalName = "foo/FromSkipped",
        access = Opcodes.ACC_PUBLIC,
        sourceFile = Some("Skipped.scala"),
      ),
      "foo/T$class" -> classBytes(
        internalName = "foo/T$class",
        access = Opcodes.ACC_PUBLIC,
      ),
      "foo/Hidden" -> classBytes(
        internalName = "foo/Hidden",
        access = 0,
      ),
      "foo/Synthetic" -> classBytes(
        internalName = "foo/Synthetic",
        access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
      ),
      "foo/PublicApi" -> classBytes(
        internalName = "foo/PublicApi",
        access = Opcodes.ACC_PUBLIC,
      ),
    )

    val result = TurbineConformanceCli.classifyBaselineOnlyClasses(
      baselineOnly = baselineClasses.keySet,
      baselineClasses = baselineClasses,
      skippedScalaFileNames = Set("Skipped.scala"),
      abiScope = AbiScope.Java,
    )

    assertEquals(result.ignoredFromSkippedSources, Set("foo/FromSkipped"))
    assertEquals(result.ignoredFromAbiScope.get("foo/T$class"), Some("scala2-trait-impl-class"))
    assertEquals(result.ignoredFromAbiScope.get("foo/Hidden"), Some("non-api-class"))
    assertEquals(result.ignoredFromAbiScope.get("foo/Synthetic"), Some("synthetic-class"))
    assertEquals(result.missing, Set("foo/PublicApi"))
  }

  test("classify-baseline-only-full-scope") {
    val baselineClasses = Map(
      "foo/FromSkipped" -> classBytes(
        internalName = "foo/FromSkipped",
        access = Opcodes.ACC_PUBLIC,
        sourceFile = Some("Skipped.scala"),
      ),
      "foo/T$class" -> classBytes(
        internalName = "foo/T$class",
        access = Opcodes.ACC_PUBLIC,
      ),
      "foo/Hidden" -> classBytes(
        internalName = "foo/Hidden",
        access = 0,
      ),
      "foo/Synthetic" -> classBytes(
        internalName = "foo/Synthetic",
        access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
      ),
      "foo/PublicApi" -> classBytes(
        internalName = "foo/PublicApi",
        access = Opcodes.ACC_PUBLIC,
      ),
    )

    val result = TurbineConformanceCli.classifyBaselineOnlyClasses(
      baselineOnly = baselineClasses.keySet,
      baselineClasses = baselineClasses,
      skippedScalaFileNames = Set("Skipped.scala"),
      abiScope = AbiScope.Full,
    )

    assertEquals(result.ignoredFromSkippedSources, Set("foo/FromSkipped"))
    assertEquals(result.ignoredFromAbiScope, Map.empty[String, String])
    assertEquals(
      result.missing,
      Set("foo/T$class", "foo/Hidden", "foo/Synthetic", "foo/PublicApi"),
    )
  }

  test("classify-baseline-only-required-java-class-from-skipped-source") {
    val baselineClasses = Map(
      "foo/FromSkipped" -> classBytes(
        internalName = "foo/FromSkipped",
        access = Opcodes.ACC_PUBLIC,
        sourceFile = Some("Skipped.scala"),
      ),
    )

    val result = TurbineConformanceCli.classifyBaselineOnlyClasses(
      baselineOnly = baselineClasses.keySet,
      baselineClasses = baselineClasses,
      skippedScalaFileNames = Set("Skipped.scala"),
      abiScope = AbiScope.JavaUsed,
      javaRequiredClasses = Set("foo/FromSkipped"),
    )

    assertEquals(result.ignoredFromSkippedSources, Set.empty[String])
    assertEquals(result.missing, Set("foo/FromSkipped"))
  }

  private def classBytes(
      internalName: String,
      access: Int,
      sourceFile: Option[String] = None,
  ): Array[Byte] = {
    val writer = new ClassWriter(0)
    writer.visit(Opcodes.V1_8, access, internalName, null, "java/lang/Object", null)
    sourceFile.foreach(file => writer.visitSource(file, null))
    val ctor: MethodVisitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    ctor.visitCode()
    ctor.visitVarInsn(Opcodes.ALOAD, 0)
    ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    ctor.visitInsn(Opcodes.RETURN)
    ctor.visitMaxs(1, 1)
    ctor.visitEnd()
    writer.visitEnd()
    writer.toByteArray
  }
}
