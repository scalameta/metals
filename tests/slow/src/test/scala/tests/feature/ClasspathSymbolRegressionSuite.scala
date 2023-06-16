package tests.feature

import java.nio.file.Files

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

import tests.BaseWorkspaceSymbolSuite
import tests.Library

abstract class ClasspathSymbolRegressionSuite extends BaseWorkspaceSymbolSuite {
  var tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("metals"))
  override def libraries: List[Library] = Library.allScala2
  def workspace: AbsolutePath = tmp
  override def afterAll(): Unit = {
    RecursivelyDelete(tmp)
  }

  check(
    "scala.None",
    """|scala.None Object
       |scala.meta.inputs.Input.None Object
       |scala.meta.inputs.Position.None Object
       |scala.meta.internal.semanticdb.Scala.Descriptor.None Object
       |scala.meta.internal.trees.Origin.None Object
       |scala.meta.prettyprinters.Show.None Object
       |scala.reflect.macros.NonemptyAttachments Class
       |scala.tools.nsc.backend.jvm.opt.LocalOptImpls.RemoveHandlersResult.NoneRemoved Object
       |scala.tools.nsc.settings.ScalaSettings#CachePolicy.None Object
       |scala.tools.nsc.transform.async.ExprBuilder#StateTransitionStyle.None Object
       |""".stripMargin,
  )
  check(
    "Map.Entry",
    """|com.esotericsoftware.kryo.util.IdentityMap#Entry Class
       |com.esotericsoftware.kryo.util.IntMap#Entry Class
       |com.esotericsoftware.kryo.util.ObjectMap#Entry Class
       |com.twitter.util.tunable.TunableMap.Entry Class
       |io.netty.util.collection.IntObjectMap#Entry Interface
       |java.util.Map#Entry Interface
       |java.util.TreeMap#AscendingSubMap#AscendingEntrySetView Class
       |java.util.TreeMap#DescendingSubMap#DescendingEntrySetView Class
       |java.util.TreeMap#Entry Class
       |java.util.TreeMap#EntryIterator Class
       |java.util.TreeMap#EntrySet Class
       |java.util.TreeMap#EntrySpliterator Class
       |java.util.TreeMap#NavigableSubMap#DescendingSubMapEntryIterator Class
       |java.util.TreeMap#NavigableSubMap#EntrySetView Class
       |java.util.TreeMap#NavigableSubMap#SubMapEntryIterator Class
       |java.util.TreeMap#PrivateEntryIterator Class
       |java.util.WeakHashMap#Entry Class
       |java.util.WeakHashMap#EntryIterator Class
       |java.util.WeakHashMap#EntrySet Class
       |java.util.WeakHashMap#EntrySpliterator Class
       |org.apache.commons.collections.ReferenceMap#Entry Class
       |org.apache.commons.collections.ReferenceMap#EntryIterator Class
       |org.apache.commons.lang.IntHashMap#Entry Class
       |""".stripMargin,
  )

  check(
    "FileStream",
    """|java.io.FileInputStream Class
       |java.io.FileOutputStream Class
       |org.antlr.v4.runtime.ANTLRFileStream Class
       |org.apache.avro.file.DataFileStream Class
       |org.apache.hadoop.mapred.IFileInputStream Class
       |org.apache.hadoop.mapred.IFileOutputStream Class
       |org.apache.spark.sql.execution.streaming.FileStreamOptions Class
       |org.apache.spark.sql.execution.streaming.FileStreamSink Class
       |org.apache.spark.sql.execution.streaming.FileStreamSink Object
       |org.apache.spark.sql.execution.streaming.FileStreamSinkLog Class
       |org.apache.spark.sql.execution.streaming.FileStreamSinkLog Object
       |org.apache.spark.sql.execution.streaming.FileStreamSource Class
       |org.apache.spark.sql.execution.streaming.FileStreamSource Object
       |""".stripMargin,
  )
  check(
    "File",
    """|acyclic.plugin.Value.File Class
       |com.google.common.io.Files Class
       |com.google.common.io.Files#FileByteSink Class
       |com.google.common.io.Files#FileByteSource Class
       |com.google.common.io.Files#FilePredicate Class
       |com.google.protobuf.compiler.PluginProtos#CodeGeneratorResponse#File Class
       |com.google.protobuf.compiler.PluginProtos#CodeGeneratorResponse#FileOrBuilder Interface
       |com.google.protobuf.compiler.plugin.CodeGeneratorResponse.File Class
       |com.google.protobuf.compiler.plugin.CodeGeneratorResponse.File Object
       |com.google.protobuf.compiler.plugin.CodeGeneratorResponse.File.FileLens Class
       |com.twitter.io.Files Object
       |io.buoyant.config.types.File Class
       |io.buoyant.config.types.FileDeserializer Class
       |io.buoyant.config.types.FileSerializer Class
       |java.io.File Class
       |java.nio.file.Files Class
       |java.nio.file.Files#FileTypeDetectors Class
       |javax.annotation.processing.Filer Interface
       |org.apache.hadoop.mapred.IFile Class
       |org.apache.parquet.Files Class
       |scala.meta.inputs.Input.File Class
       |scala.meta.inputs.Input.File Object
       |scala.meta.inputs.Input.VirtualFile Class
       |scala.reflect.io.File Class
       |scala.reflect.io.File Object
       |sourcecode.File Class
       |sourcecode.File Object
       |""".stripMargin,
  )
  check(
    "Files",
    """|com.google.common.io.Files Class
       |com.google.common.io.MoreFiles Class
       |com.twitter.io.Files Object
       |java.nio.file.Files Class
       |javax.swing.plaf.basic.BasicDirectoryModel#FilesLoader Class
       |org.apache.hadoop.mapred.MROutputFiles Class
       |org.apache.hadoop.mapred.YarnOutputFiles Class
       |org.apache.ivy.ant.IvyCacheFileset Class
       |org.apache.parquet.Files Class
       |org.apache.spark.SparkFiles Object
       |org.apache.spark.sql.execution.streaming.FileStreamSource.SeenFilesMap Class
       |org.glassfish.jersey.server.internal.scanning.FilesScanner Class
       |org.jline.builtins.Completers#FilesCompleter Class
       |scala.meta.internal.io.ListFiles Class
       |""".stripMargin,
  )

  check(
    "Implicits",
    """|akka.stream.extra.Implicits Object
       |akka.stream.scaladsl.GraphDSL.Implicits Object
       |com.fasterxml.jackson.module.scala.util.Implicits Object
       |fastparse.Implicits Object
       |kafka.javaapi.Implicits Object
       |kafka.utils.Implicits Object
       |org.apache.spark.sql.LowPrioritySQLImplicits Interface
       |org.apache.spark.sql.SQLImplicits Class
       |org.json4s.Implicits Interface
       |scala.concurrent.ExecutionContext.Implicits Object
       |scala.math.Equiv.ExtraImplicits Interface
       |scala.math.Equiv.Implicits Object
       |scala.math.Fractional.ExtraImplicits Interface
       |scala.math.Fractional.Implicits Object
       |scala.math.Integral.ExtraImplicits Interface
       |scala.math.Integral.Implicits Object
       |scala.math.LowPriorityOrderingImplicits Interface
       |scala.math.Numeric.ExtraImplicits Interface
       |scala.math.Numeric.Implicits Object
       |scala.math.Ordering.ExtraImplicits Interface
       |scala.math.Ordering.Implicits Object
       |scala.meta.internal.fastparse.core.Implicits Object
       |scala.tools.nsc.interpreter.Power#Implicits1 Interface
       |scala.tools.nsc.interpreter.Power#Implicits2 Interface
       |scala.tools.nsc.interpreter.StdReplVals#ReplImplicits Class
       |scala.tools.nsc.typechecker.Implicits Interface
       |scala.tools.nsc.typechecker.ImplicitsStats Interface
       |""".stripMargin,
  )

  check(
    "collection.TrieMap",
    """|scala.collection.concurrent.TrieMap Class
       |scala.collection.concurrent.TrieMap Object
       |scala.collection.concurrent.TrieMapIterator Class
       |scala.collection.concurrent.TrieMapSerializationEnd Object
       |""".stripMargin,
  )

  check(
    "inputs.Position.",
    """|scala.meta.inputs.Position.None Object
       |scala.meta.inputs.Position.Range Class
       |scala.meta.inputs.Position.Range Object
       |""".stripMargin,
  )

  check(
    "Input.None",
    """|scala.meta.inputs.Input.None Object
       |""".stripMargin,
  )

  check(
    "IO",
    """|akka.io.IO Object
       |com.typesafe.config.ConfigException#IO Class
       |org.eclipse.jetty.util.IO Class
       |org.mortbay.util.IO Class
       |""".stripMargin,
  )

}

class VirtualDocsClasspathSymbolRegressionSuite
    extends ClasspathSymbolRegressionSuite {
  override def saveClassFileToDisk: Boolean = false
}

class SaveToDiskClasspathSymbolRegressionSuite
    extends ClasspathSymbolRegressionSuite {
  override def saveClassFileToDisk: Boolean = true
}
