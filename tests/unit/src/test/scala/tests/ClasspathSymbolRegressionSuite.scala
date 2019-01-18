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

  check(
    "scala.None",
    "scala.None Object"
  )
  check(
    "Map.Entry",
    """|com.esotericsoftware.kryo.util.IdentityMap#Entry Class
       |com.esotericsoftware.kryo.util.IntMap#Entry Class
       |com.esotericsoftware.kryo.util.ObjectMap#Entry Class
       |com.google.common.collect.HashBiMap#BiEntry Class
       |com.google.common.collect.Maps#EntryFunction Class
       |com.google.common.collect.Maps#EntrySet Class
       |com.google.common.collect.Maps#EntryTransformer Interface
       |com.google.common.collect.Maps#FilteredEntryBiMap Class
       |com.google.common.collect.Maps#FilteredEntryMap Class
       |com.google.common.collect.Maps#FilteredEntryMap#EntrySet Class
       |com.google.common.collect.Maps#FilteredEntryNavigableMap Class
       |com.google.common.collect.Maps#FilteredEntrySortedMap Class
       |com.google.common.collect.Maps#UnmodifiableEntrySet Class
       |com.twitter.util.tunable.TunableMap.Entry Class
       |jersey.repackaged.com.google.common.collect.Maps#EntryFunction Class
       |jersey.repackaged.com.google.common.collect.Maps#EntrySet Class
       |jersey.repackaged.com.google.common.collect.Maps#EntryTransformer Interface
       |jersey.repackaged.com.google.common.collect.Maps#FilteredEntryBiMap Class
       |jersey.repackaged.com.google.common.collect.Maps#FilteredEntryMap Class
       |jersey.repackaged.com.google.common.collect.Maps#FilteredEntryMap#EntrySet Class
       |jersey.repackaged.com.google.common.collect.Maps#FilteredEntryNavigableMap Class
       |jersey.repackaged.com.google.common.collect.Maps#FilteredEntrySortedMap Class
       |jersey.repackaged.com.google.common.collect.Maps#UnmodifiableEntrySet Class
       |org.apache.commons.collections.BeanMap#MyMapEntry Class
       |org.apache.commons.collections.map.Flat3Map#EntrySet Class
       |org.apache.commons.collections.map.Flat3Map#EntrySetIterator Class
       |org.apache.commons.lang.IntHashMap#Entry Class
       |""".stripMargin
  )

  check(
    "FileStream",
    """|com.ning.compress.lzf.util.LZFFileInputStream Class
       |org.antlr.v4.runtime.ANTLRFileStream Class
       |org.apache.avro.file.DataFileStream Class
       |org.apache.hadoop.mapred.IFileInputStream Class
       |org.apache.hadoop.mapred.IFileOutputStream Class
       |org.apache.kafka.common.record.FileLogInputStream Class
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
       |com.twitter.io.Files Object
       |io.buoyant.config.types.File Class
       |io.buoyant.config.types.FileDeserializer Class
       |io.buoyant.config.types.FileSerializer Class
       |org.apache.hadoop.io.file.tfile.BCFile Class
       |org.apache.hadoop.io.file.tfile.TFile Class
       |org.apache.hadoop.io.file.tfile.TFile#TFileIndex Class
       |org.apache.hadoop.io.file.tfile.TFile#TFileIndexEntry Class
       |org.apache.hadoop.io.file.tfile.TFile#TFileMeta Class
       |org.apache.hadoop.mapred.IFile Class
       |org.apache.hadoop.record.compiler.JFile Class
       |org.apache.jute.compiler.JFile Class
       |org.apache.parquet.Files Class
       |org.langmeta.internal.io.FileIO Object
       |scala.reflect.internal.util.BatchSourceFile Class
       |scala.reflect.internal.util.NoFile Object
       |scala.reflect.internal.util.NoSourceFile Object
       |scala.reflect.internal.util.ScriptSourceFile Class
       |scala.reflect.internal.util.ScriptSourceFile Object
       |scala.reflect.internal.util.SourceFile Class
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
       |org.apache.hadoop.mapred.MROutputFiles Class
       |org.apache.hadoop.mapred.YarnOutputFiles Class
       |org.apache.hadoop.mapreduce.JobSubmissionFiles Class
       |org.apache.ivy.ant.IvyCacheFileset Class
       |org.apache.parquet.Files Class
       |org.apache.spark.SparkFiles Object
       |org.apache.spark.sql.execution.command.ListFilesCommand Class
       |org.apache.spark.sql.execution.streaming.FileStreamSource.SeenFilesMap Class
       |org.glassfish.jersey.server.internal.scanning.FilesScanner Class
       |org.langmeta.internal.io.ListFiles Class
       |""".stripMargin
  )

  check(
    "Implicits",
    """|com.fasterxml.jackson.module.scala.util.Implicits Object
       |fastparse.core.Implicits Object
       |kafka.javaapi.Implicits Object
       |kafka.javaapi.MetadataListImplicits Object
       |kafka.utils.Implicits Object
       |org.apache.spark.sql.LowPrioritySQLImplicits Interface
       |org.apache.spark.sql.SQLImplicits Class
       |org.json4s.DynamicJValueImplicits Interface
       |org.json4s.Implicits Interface
       |scala.LowPriorityImplicits Class
       |scala.collection.convert.ToJavaImplicits Interface
       |scala.collection.convert.ToScalaImplicits Interface
       |scala.math.Integral.ExtraImplicits Interface
       |scala.math.Integral.Implicits Object
       |scala.math.LowPriorityOrderingImplicits Interface
       |scala.math.Numeric.ExtraImplicits Interface
       |scala.math.Numeric.Implicits Object
       |scala.math.Ordering.ExtraImplicits Interface
       |scala.math.Ordering.Implicits Object
       |scala.sys.process.ProcessImplicits Interface
       |scala.tools.nsc.interpreter.Power#LowPriorityPrettifier#AnyPrettifier.Implicits1 Interface
       |scala.tools.nsc.interpreter.Power#LowPriorityPrettifier#AnyPrettifier.Implicits2 Interface
       |scala.tools.nsc.typechecker.Implicits Interface
       |scala.tools.nsc.typechecker.ImplicitsStats Object
       |""".stripMargin
  )

}
