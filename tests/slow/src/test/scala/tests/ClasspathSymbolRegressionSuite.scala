package tests

import java.nio.file.Files
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

object ClasspathSymbolRegressionSuite extends BaseWorkspaceSymbolSuite {
  var tmp = AbsolutePath(Files.createTempDirectory("metals"))
  override def libraries: List[Library] = Library.all
  def workspace: AbsolutePath = tmp
  override def afterAll(): Unit = {
    RecursivelyDelete(tmp)
  }
  override def check(query: String, expected: String): Unit = {
    if (isAppveyor) {
      // Ignored on Appveyor because the JDK classpath is different.
      ignore(query)(())
    } else {
      super.check(query, expected)
    }
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
       |""".stripMargin
  )
  check(
    "Map.Entry",
    """|com.esotericsoftware.kryo.util.IdentityMap#Entry Class
       |com.esotericsoftware.kryo.util.IntMap#Entry Class
       |com.esotericsoftware.kryo.util.ObjectMap#Entry Class
       |com.twitter.util.tunable.TunableMap.Entry Class
       |io.netty.util.collection.IntObjectMap#PrimitiveEntry Interface
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
       |""".stripMargin
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
       |""".stripMargin
  )
  check(
    "File",
    """|com.google.common.io.Files Class
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
       |org.apache.hadoop.mapred.IFile Class
       |org.apache.hadoop.record.compiler.JFile Class
       |org.apache.jute.compiler.JFile Class
       |org.apache.parquet.Files Class
       |scala.meta.inputs.Input.Stream.SerializationProxy#File Class
       |scala.meta.inputs.Input.Stream.SerializationProxy#File Object
       |scala.meta.inputs.Input.Stream.SerializationProxy#File.SerializationProxy#VirtualFile Class
       |scala.reflect.io.File Class
       |scala.reflect.io.File Object
       |sourcecode.File Class
       |sourcecode.File Object
       |""".stripMargin
  )
  check(
    "Files",
    """|com.google.common.io.Files Class
       |com.google.common.io.MoreFiles Class
       |com.twitter.io.Files Object
       |java.nio.file.Files Class
       |org.apache.hadoop.mapred.MROutputFiles Class
       |org.apache.hadoop.mapred.YarnOutputFiles Class
       |org.apache.ivy.ant.IvyCacheFileset Class
       |org.apache.parquet.Files Class
       |org.apache.spark.SparkFiles Object
       |org.apache.spark.sql.execution.command.ListFilesCommand Class
       |org.apache.spark.sql.execution.streaming.FileStreamSource.SeenFilesMap Class
       |org.glassfish.jersey.server.internal.scanning.FilesScanner Class
       |scala.meta.internal.io.ListFiles Class
       |scala.tools.nsc.interactive.CompilerControl#FilesDeletedItem Class
       |""".stripMargin
  )

  check(
    "Implicits",
    """|akka.stream.extra.Implicits Object
       |akka.stream.scaladsl.GraphDSL.Implicits Object
       |com.fasterxml.jackson.module.scala.util.Implicits Object
       |fastparse.core.Implicits Object
       |kafka.javaapi.Implicits Object
       |kafka.utils.Implicits Object
       |org.apache.spark.sql.LowPrioritySQLImplicits Interface
       |org.apache.spark.sql.SQLImplicits Class
       |org.json4s.Implicits Interface
       |scala.collection.convert.ToJavaImplicits Interface
       |scala.collection.convert.ToScalaImplicits Interface
       |scala.concurrent.ExecutionContext.Implicits Object
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
       |scala.tools.nsc.interpreter.Power#LowPriorityPrettifier#AnyPrettifier.Implicits1 Interface
       |scala.tools.nsc.interpreter.Power#LowPriorityPrettifier#AnyPrettifier.Implicits2 Interface
       |scala.tools.nsc.interpreter.StdReplVals#ReplImplicits Class
       |scala.tools.nsc.typechecker.Implicits Interface
       |scala.tools.nsc.typechecker.ImplicitsStats Object
       |""".stripMargin
  )

  check(
    "collection.TrieMap",
    """|scala.collection.concurrent.TrieMap Class
       |scala.collection.concurrent.TrieMap Object
       |scala.collection.concurrent.TrieMapIterator Class
       |scala.collection.concurrent.TrieMapSerializationEnd Object
       |scala.collection.immutable.HashMap.HashTrieMap Class
       |scala.collection.parallel.mutable.ParTrieMap Class
       |scala.collection.parallel.mutable.ParTrieMap Object
       |scala.collection.parallel.mutable.ParTrieMapCombiner Interface
       |scala.collection.parallel.mutable.ParTrieMapSplitter Class
       |""".stripMargin
  )

  check(
    "inputs.Position.",
    """|scala.meta.inputs.Position.None Object
       |scala.meta.inputs.Position.Range Class
       |scala.meta.inputs.Position.Range Object
       |""".stripMargin
  )

  check(
    "Input.None",
    """|scala.meta.inputs.Input.None Object
       |""".stripMargin
  )

}
