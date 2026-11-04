package tests.pc

import java.nio.file.Paths

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.pc.ScalaPresentationCompiler

class OverloadSymbolFailureSuite extends munit.FunSuite {
  private def withCompiler(
      check: scala.meta.internal.pc.MetalsGlobal => Unit
  ): Unit = {
    val presentationCompiler = new ScalaPresentationCompiler()
      .newInstance(
        "overload-failure",
        List(
          Paths.get(
            classOf[
              Option[_]
            ].getProtectionDomain.getCodeSource.getLocation.toURI
          )
        ).asJava,
        Nil.asJava
      )
      .asInstanceOf[ScalaPresentationCompiler]
    val compiler = presentationCompiler.newCompiler()
    try check(compiler)
    finally {
      compiler.askShutdown()
      presentationCompiler.shutdown()
    }
  }

  test("owner-lookup-failure-is-not-an-empty-symbol") {
    withCompiler { global =>
      import global._
      val failure = new IllegalStateException("owner lookup failed")
      val owner = EmptyPackageClass.newClassSymbol(TypeName("Owner"))
      owner.setInfo(new LazyType {
        override def complete(symbol: Symbol): Unit = throw failure
      })
      val selected = owner.newMethodSymbol(TermName("selected"))
      selected.setInfo(NullaryMethodType(definitions.IntTpe))
      val obtained = intercept[IllegalStateException] {
        semanticdbSymbol(selected)
      }
      assertEquals(obtained, failure)
    }
  }

  test("overload-comparison-failure-does-not-reuse-orphan") {
    withCompiler { global =>
      import global._
      val owner = EmptyPackageClass.newClassSymbol(TypeName("Owner"))
      val declarations = newScope
      owner.setInfo(
        ClassInfoType(List(definitions.ObjectTpe), declarations, owner)
      )
      val selected = owner.newMethodSymbol(TermName("selected"))
      selected.setInfo(NullaryMethodType(definitions.IntTpe))
      val first = owner.newMethodSymbol(TermName("selected"))
      first.setInfo(new LazyType {
        override def complete(symbol: Symbol): Unit =
          throw new IllegalStateException("type comparison failed")
      })
      val second = owner.newMethodSymbol(TermName("selected"))
      second.setInfo(NullaryMethodType(definitions.StringTpe))
      declarations.enter(first)
      declarations.enter(second)
      assertEquals(semanticdbSymbol(selected), "")
    }
  }
}
