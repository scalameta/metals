package tests.pc

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.DefinitionResult

abstract class BaseJavaTypeDefinitionSuite extends BaseJavaDefinitionSuite {
  override def doDefinitionRequest(
      params: CompilerOffsetParams
  ): DefinitionResult = {
    presentationCompiler.typeDefinition(params).get()
  }
}
