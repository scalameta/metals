package tests.ctags

import java.nio.file.Paths
import scala.meta.languageserver.Jars
import scala.meta.languageserver.ctags.Ctags
import org.langmeta.internal.semanticdb.schema.Database
import tests.MegaSuite

object ClasspathCtagsTest extends MegaSuite {

  // NOTE(olafur) this test is a bit slow since it downloads jars from the internet.
  ignore("index classpath") {
    val classpath = Jars.fetch(
      "com.lihaoyi",
      "sourcecode_2.12",
      "0.1.4",
      System.out,
      fetchSourceJars = true
    )
    val Compat = Paths.get("sourcecode").resolve("Compat.scala")
    val SourceContext = Paths.get("sourcecode").resolve("SourceContext.scala")
    val Predef = Paths.get("scala").resolve("io").resolve("AnsiColor.scala")
    val CharRef = Paths.get("scala").resolve("runtime").resolve("CharRef.java")
    val docs = List.newBuilder[String]
    Ctags.index(
      classpath,
      shouldIndex = { path =>
        path.toNIO.endsWith(CharRef) ||
        path.toNIO.endsWith(Compat) ||
        path.toNIO.endsWith(SourceContext) ||
        path.toNIO.endsWith(Predef)
      }
    ) { doc =>
      val path = Paths.get(doc.filename).getFileName.toString
      val underline = "-" * path.length
      val mdoc = Database(doc :: Nil).toDb(None).documents.head.toString()
      docs +=
        s"""$path
           |$underline
           |
           |$mdoc""".stripMargin
    }
    val obtained = docs.result().sorted.mkString("\n\n")
    val expected =
      """
        |AnsiColor.scala
        |---------------
        |
        |Language:
        |Scala212
        |
        |Names:
        |[3580..3589): AnsiColor <= _root_.scala.io.AnsiColor#
        |[3674..3679): BLACK <= _root_.scala.io.AnsiColor#BLACK.
        |[3778..3781): RED <= _root_.scala.io.AnsiColor#RED.
        |[3886..3891): GREEN <= _root_.scala.io.AnsiColor#GREEN.
        |[3996..4002): YELLOW <= _root_.scala.io.AnsiColor#YELLOW.
        |[4102..4106): BLUE <= _root_.scala.io.AnsiColor#BLUE.
        |[4214..4221): MAGENTA <= _root_.scala.io.AnsiColor#MAGENTA.
        |[4320..4324): CYAN <= _root_.scala.io.AnsiColor#CYAN.
        |[4428..4433): WHITE <= _root_.scala.io.AnsiColor#WHITE.
        |[4537..4544): BLACK_B <= _root_.scala.io.AnsiColor#BLACK_B.
        |[4641..4646): RED_B <= _root_.scala.io.AnsiColor#RED_B.
        |[4749..4756): GREEN_B <= _root_.scala.io.AnsiColor#GREEN_B.
        |[4859..4867): YELLOW_B <= _root_.scala.io.AnsiColor#YELLOW_B.
        |[4965..4971): BLUE_B <= _root_.scala.io.AnsiColor#BLUE_B.
        |[5077..5086): MAGENTA_B <= _root_.scala.io.AnsiColor#MAGENTA_B.
        |[5183..5189): CYAN_B <= _root_.scala.io.AnsiColor#CYAN_B.
        |[5291..5298): WHITE_B <= _root_.scala.io.AnsiColor#WHITE_B.
        |[5388..5393): RESET <= _root_.scala.io.AnsiColor#RESET.
        |[5475..5479): BOLD <= _root_.scala.io.AnsiColor#BOLD.
        |[5568..5578): UNDERLINED <= _root_.scala.io.AnsiColor#UNDERLINED.
        |[5656..5661): BLINK <= _root_.scala.io.AnsiColor#BLINK.
        |[5747..5755): REVERSED <= _root_.scala.io.AnsiColor#REVERSED.
        |[5839..5848): INVISIBLE <= _root_.scala.io.AnsiColor#INVISIBLE.
        |[5874..5883): AnsiColor <= _root_.scala.io.AnsiColor.
        |
        |Symbols:
        |_root_.scala.io.AnsiColor# => trait AnsiColor
        |_root_.scala.io.AnsiColor#BLACK. => def BLACK
        |_root_.scala.io.AnsiColor#BLACK_B. => def BLACK_B
        |_root_.scala.io.AnsiColor#BLINK. => def BLINK
        |_root_.scala.io.AnsiColor#BLUE. => def BLUE
        |_root_.scala.io.AnsiColor#BLUE_B. => def BLUE_B
        |_root_.scala.io.AnsiColor#BOLD. => def BOLD
        |_root_.scala.io.AnsiColor#CYAN. => def CYAN
        |_root_.scala.io.AnsiColor#CYAN_B. => def CYAN_B
        |_root_.scala.io.AnsiColor#GREEN. => def GREEN
        |_root_.scala.io.AnsiColor#GREEN_B. => def GREEN_B
        |_root_.scala.io.AnsiColor#INVISIBLE. => def INVISIBLE
        |_root_.scala.io.AnsiColor#MAGENTA. => def MAGENTA
        |_root_.scala.io.AnsiColor#MAGENTA_B. => def MAGENTA_B
        |_root_.scala.io.AnsiColor#RED. => def RED
        |_root_.scala.io.AnsiColor#RED_B. => def RED_B
        |_root_.scala.io.AnsiColor#RESET. => def RESET
        |_root_.scala.io.AnsiColor#REVERSED. => def REVERSED
        |_root_.scala.io.AnsiColor#UNDERLINED. => def UNDERLINED
        |_root_.scala.io.AnsiColor#WHITE. => def WHITE
        |_root_.scala.io.AnsiColor#WHITE_B. => def WHITE_B
        |_root_.scala.io.AnsiColor#YELLOW. => def YELLOW
        |_root_.scala.io.AnsiColor#YELLOW_B. => def YELLOW_B
        |_root_.scala.io.AnsiColor. => object AnsiColor
        |
        |
        |CharRef.java
        |------------
        |
        |Language:
        |Java
        |
        |Names:
        |[267..272): scala => _root_.scala.
        |[542..549): runtime => _root_.scala.runtime.
        |[566..573): CharRef <= _root_.scala.runtime.CharRef.
        |[566..573): CharRef <= _root_.scala.runtime.CharRef#
        |[638..654): serialVersionUID <= _root_.scala.runtime.CharRef.serialVersionUID.
        |[696..700): elem <= _root_.scala.runtime.CharRef#elem.
        |[772..780): toString <= _root_.scala.runtime.CharRef#toString.
        |[857..863): create <= _root_.scala.runtime.CharRef.create.
        |[925..929): zero <= _root_.scala.runtime.CharRef.zero.
        |
        |Symbols:
        |_root_.scala. => package scala
        |_root_.scala.runtime. => package runtime
        |_root_.scala.runtime.CharRef# => class CharRef
        |_root_.scala.runtime.CharRef#elem. => var elem
        |_root_.scala.runtime.CharRef#toString. => def toString
        |_root_.scala.runtime.CharRef. => object CharRef
        |_root_.scala.runtime.CharRef.create. => def create
        |_root_.scala.runtime.CharRef.serialVersionUID. => val serialVersionUID
        |_root_.scala.runtime.CharRef.zero. => def zero
        |
        |
        |Compat.scala
        |------------
        |
        |Language:
        |Scala212
        |
        |Names:
        |[27..33): Compat <= _root_.sourcecode.Compat.
        |[96..110): enclosingOwner <= _root_.sourcecode.Compat.enclosingOwner.
        |[158..176): enclosingParamList <= _root_.sourcecode.Compat.enclosingParamList.
        |
        |Symbols:
        |_root_.sourcecode.Compat. => object Compat
        |_root_.sourcecode.Compat.enclosingOwner. => def enclosingOwner
        |_root_.sourcecode.Compat.enclosingParamList. => def enclosingParamList
        |
        |
        |SourceContext.scala
        |-------------------
        |
        |Language:
        |Scala212
        |
        |Names:
        |[65..69): Util <= _root_.sourcecode.Util.
        |[77..88): isSynthetic <= _root_.sourcecode.Util.isSynthetic.
        |[160..175): isSyntheticName <= _root_.sourcecode.Util.isSyntheticName.
        |[279..286): getName <= _root_.sourcecode.Util.getName.
        |[367..378): SourceValue <= _root_.sourcecode.SourceValue#
        |[415..430): SourceCompanion <= _root_.sourcecode.SourceCompanion#
        |[477..482): apply <= _root_.sourcecode.SourceCompanion#apply.
        |[528..532): wrap <= _root_.sourcecode.SourceCompanion#wrap.
        |[566..570): Name <= _root_.sourcecode.Name#
        |[621..625): Name <= _root_.sourcecode.Name.
        |[728..732): impl <= _root_.sourcecode.Name.impl.
        |[1015..1022): Machine <= _root_.sourcecode.Name.Machine#
        |[1075..1082): Machine <= _root_.sourcecode.Name.Machine.
        |[1197..1201): impl <= _root_.sourcecode.Name.Machine.impl.
        |[1435..1443): FullName <= _root_.sourcecode.FullName#
        |[1494..1502): FullName <= _root_.sourcecode.FullName.
        |[1617..1621): impl <= _root_.sourcecode.FullName.impl.
        |[1952..1959): Machine <= _root_.sourcecode.FullName.Machine#
        |[2012..2019): Machine <= _root_.sourcecode.FullName.Machine.
        |[2135..2139): impl <= _root_.sourcecode.FullName.Machine.impl.
        |[2366..2370): File <= _root_.sourcecode.File#
        |[2421..2425): File <= _root_.sourcecode.File.
        |[2539..2543): impl <= _root_.sourcecode.File.impl.
        |[2735..2739): Line <= _root_.sourcecode.Line#
        |[2784..2788): Line <= _root_.sourcecode.Line.
        |[2898..2902): impl <= _root_.sourcecode.Line.impl.
        |[3087..3096): Enclosing <= _root_.sourcecode.Enclosing#
        |[3148..3157): Enclosing <= _root_.sourcecode.Enclosing.
        |[3274..3278): impl <= _root_.sourcecode.Enclosing.impl.
        |[3395..3402): Machine <= _root_.sourcecode.Enclosing.Machine#
        |[3455..3462): Machine <= _root_.sourcecode.Enclosing.Machine.
        |[3577..3581): impl <= _root_.sourcecode.Enclosing.Machine.impl.
        |[3678..3681): Pkg <= _root_.sourcecode.Pkg#
        |[3732..3735): Pkg <= _root_.sourcecode.Pkg.
        |[3834..3838): impl <= _root_.sourcecode.Pkg.impl.
        |[3924..3928): Text <= _root_.sourcecode.Text#
        |[3965..3969): Text <= _root_.sourcecode.Text.
        |[4102..4106): Args <= _root_.sourcecode.Args#
        |[4179..4183): Args <= _root_.sourcecode.Args.
        |[4297..4301): impl <= _root_.sourcecode.Args.impl.
        |[4627..4632): Impls <= _root_.sourcecode.Impls.
        |[4640..4644): text <= _root_.sourcecode.Impls.text.
        |[5312..5317): Chunk <= _root_.sourcecode.Impls.Chunk#
        |[5327..5332): Chunk <= _root_.sourcecode.Impls.Chunk.
        |[5349..5352): Pkg <= _root_.sourcecode.Impls.Chunk.Pkg#
        |[5396..5399): Obj <= _root_.sourcecode.Impls.Chunk.Obj#
        |[5443..5446): Cls <= _root_.sourcecode.Impls.Chunk.Cls#
        |[5490..5493): Trt <= _root_.sourcecode.Impls.Chunk.Trt#
        |[5537..5540): Val <= _root_.sourcecode.Impls.Chunk.Val#
        |[5584..5587): Var <= _root_.sourcecode.Impls.Chunk.Var#
        |[5631..5634): Lzy <= _root_.sourcecode.Impls.Chunk.Lzy#
        |[5678..5681): Def <= _root_.sourcecode.Impls.Chunk.Def#
        |[5722..5731): enclosing <= _root_.sourcecode.Impls.enclosing.
        |
        |Symbols:
        |_root_.sourcecode.Args# => class Args
        |_root_.sourcecode.Args. => object Args
        |_root_.sourcecode.Args.impl. => def impl
        |_root_.sourcecode.Enclosing# => class Enclosing
        |_root_.sourcecode.Enclosing. => object Enclosing
        |_root_.sourcecode.Enclosing.Machine# => class Machine
        |_root_.sourcecode.Enclosing.Machine. => object Machine
        |_root_.sourcecode.Enclosing.Machine.impl. => def impl
        |_root_.sourcecode.Enclosing.impl. => def impl
        |_root_.sourcecode.File# => class File
        |_root_.sourcecode.File. => object File
        |_root_.sourcecode.File.impl. => def impl
        |_root_.sourcecode.FullName# => class FullName
        |_root_.sourcecode.FullName. => object FullName
        |_root_.sourcecode.FullName.Machine# => class Machine
        |_root_.sourcecode.FullName.Machine. => object Machine
        |_root_.sourcecode.FullName.Machine.impl. => def impl
        |_root_.sourcecode.FullName.impl. => def impl
        |_root_.sourcecode.Impls. => object Impls
        |_root_.sourcecode.Impls.Chunk# => trait Chunk
        |_root_.sourcecode.Impls.Chunk. => object Chunk
        |_root_.sourcecode.Impls.Chunk.Cls# => class Cls
        |_root_.sourcecode.Impls.Chunk.Def# => class Def
        |_root_.sourcecode.Impls.Chunk.Lzy# => class Lzy
        |_root_.sourcecode.Impls.Chunk.Obj# => class Obj
        |_root_.sourcecode.Impls.Chunk.Pkg# => class Pkg
        |_root_.sourcecode.Impls.Chunk.Trt# => class Trt
        |_root_.sourcecode.Impls.Chunk.Val# => class Val
        |_root_.sourcecode.Impls.Chunk.Var# => class Var
        |_root_.sourcecode.Impls.enclosing. => def enclosing
        |_root_.sourcecode.Impls.text. => def text
        |_root_.sourcecode.Line# => class Line
        |_root_.sourcecode.Line. => object Line
        |_root_.sourcecode.Line.impl. => def impl
        |_root_.sourcecode.Name# => class Name
        |_root_.sourcecode.Name. => object Name
        |_root_.sourcecode.Name.Machine# => class Machine
        |_root_.sourcecode.Name.Machine. => object Machine
        |_root_.sourcecode.Name.Machine.impl. => def impl
        |_root_.sourcecode.Name.impl. => def impl
        |_root_.sourcecode.Pkg# => class Pkg
        |_root_.sourcecode.Pkg. => object Pkg
        |_root_.sourcecode.Pkg.impl. => def impl
        |_root_.sourcecode.SourceCompanion# => class SourceCompanion
        |_root_.sourcecode.SourceCompanion#apply. => def apply
        |_root_.sourcecode.SourceCompanion#wrap. => def wrap
        |_root_.sourcecode.SourceValue# => class SourceValue
        |_root_.sourcecode.Text# => class Text
        |_root_.sourcecode.Text. => object Text
        |_root_.sourcecode.Util. => object Util
        |_root_.sourcecode.Util.getName. => def getName
        |_root_.sourcecode.Util.isSynthetic. => def isSynthetic
        |_root_.sourcecode.Util.isSyntheticName. => def isSyntheticName
      """.stripMargin
    assertNoDiff(obtained, expected)
  }
}
