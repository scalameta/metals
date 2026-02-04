package com.google.turbine.testing

import com.google.common.base.Joiner
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.List
import java.util.Set
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InnerClassNode
import org.objectweb.asm.tree.TypeAnnotationNode
import scala.jdk.CollectionConverters._
import scala.collection.immutable.Map

object IntegrationTestSupport {
  def sortMembers(in: Map[String, Array[Byte]]): Map[String, Array[Byte]] = {
    val classes = toClassNodes(in)
    val it = classes.iterator()
    while (it.hasNext) {
      sortAttributes(it.next())
    }
    toByteCode(classes)
  }

  def canonicalize(in: Map[String, Array[Byte]]): Map[String, Array[Byte]] = {
    var classes = toClassNodes(in)
    classes =
      classes.asScala
        .filterNot(n => isAnonymous(n) || isLocal(n))
        .asJava

    val infos = new HashMap[String, InnerClassNode]()
    val itInfos = classes.iterator()
    while (itInfos.hasNext) {
      val n = itInfos.next()
      n.innerClasses.forEach(inner => infos.put(inner.name, inner))
    }

    val all = new HashSet[String]()
    classes.forEach(n => all.add(n.name))

    val it = classes.iterator()
    while (it.hasNext) {
      val n = it.next()
      removeImplementation(n)
      removeUnusedInnerClassAttributes(infos, n)
      makeEnumsNonAbstract(all, n)
      sortAttributes(n)
      undeprecate(n)
      removePreviewVersion(n)
    }

    toByteCode(classes)
  }

  def dump(compiled: Map[String, Array[Byte]]): String = {
    val sb = new StringBuilder
    val keys = new ArrayList[String](compiled.keySet.asJava)
    Collections.sort(keys)
    keys.forEach { key =>
      val normalized = if (key.startsWith("/")) key.substring(1) else key
      sb.append(s"=== $normalized ===\n")
      sb.append(AsmUtils.textify(compiled(key), skipDebug = true))
    }
    sb.toString()
  }

  private def isLocal(n: ClassNode): Boolean = n.outerMethod != null

  private def isAnonymous(n: ClassNode): Boolean =
    n.innerClasses.asScala.exists(i => i.name == n.name && i.innerName == null)

  private def undeprecate(n: ClassNode): Unit = {
    if (!isDeprecated(n.visibleAnnotations)) {
      n.access = n.access & ~Opcodes.ACC_DEPRECATED
    }
    n.methods.asScala
      .filter(m => !isDeprecated(m.visibleAnnotations))
      .foreach(m => m.access = m.access & ~Opcodes.ACC_DEPRECATED)
    n.fields.asScala
      .filter(f => !isDeprecated(f.visibleAnnotations))
      .foreach(f => f.access = f.access & ~Opcodes.ACC_DEPRECATED)
  }

  private def removePreviewVersion(n: ClassNode): Unit = {
    n.version = n.version & 0xffff
  }

  private def isDeprecated(visibleAnnotations: List[AnnotationNode]): Boolean = {
    visibleAnnotations != null && visibleAnnotations.asScala.exists(_.desc == "Ljava/lang/Deprecated;")
  }

  private def makeEnumsNonAbstract(all: Set[String], n: ClassNode): Unit = {
    n.innerClasses.forEach { inner =>
      if (all.contains(inner.name) && (inner.access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) {
        inner.access = inner.access & ~Opcodes.ACC_ABSTRACT
      }
    }
    if ((n.access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) {
      n.access = n.access & ~Opcodes.ACC_ABSTRACT
    }
  }

  private def toByteCode(classes: List[ClassNode]): Map[String, Array[Byte]] = {
    val out = new LinkedHashMap[String, Array[Byte]]()
    classes.forEach { n =>
      val cw = new ClassWriter(0)
      n.accept(cw)
      out.put(n.name, cw.toByteArray)
    }
    out.asScala.toMap
  }

  private def toClassNodes(in: Map[String, Array[Byte]]): List[ClassNode] = {
    val classes = new ArrayList[ClassNode]()
    in.values.foreach { bytes =>
      val n = new ClassNode()
      new ClassReader(bytes).accept(n, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
      classes.add(n)
    }
    classes
  }

  private def removeImplementation(n: ClassNode): Unit = {
    n.innerClasses =
      n.innerClasses.asScala
        .filter(x => (x.access & Opcodes.ACC_SYNTHETIC) == 0 && x.innerName != null)
        .asJava

    n.methods =
      n.methods.asScala
        .filter(x => (x.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_PRIVATE)) == 0)
        .filter(x => x.name != "<clinit>")
        .asJava

    n.fields =
      n.fields.asScala
        .filter(x => (x.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_PRIVATE)) == 0)
        .asJava
  }

  private def sortAttributes(n: ClassNode): Unit = {
    n.innerClasses.sort(
      Comparator
        .comparing((x: InnerClassNode) => x.name)
        .thenComparing(x => x.outerName)
        .thenComparing(x => x.innerName)
        .thenComparingInt(x => x.access)
    )

    sortAnnotations(n.visibleAnnotations)
    sortAnnotations(n.invisibleAnnotations)
    sortTypeAnnotations(n.visibleTypeAnnotations)
    sortTypeAnnotations(n.invisibleTypeAnnotations)

    n.methods.forEach { m =>
      sortParameterAnnotations(m.visibleParameterAnnotations)
      sortParameterAnnotations(m.invisibleParameterAnnotations)

      sortAnnotations(m.visibleAnnotations)
      sortAnnotations(m.invisibleAnnotations)
      sortTypeAnnotations(m.visibleTypeAnnotations)
      sortTypeAnnotations(m.invisibleTypeAnnotations)
    }

    n.fields.forEach { f =>
      sortAnnotations(f.visibleAnnotations)
      sortAnnotations(f.invisibleAnnotations)
      sortTypeAnnotations(f.visibleTypeAnnotations)
      sortTypeAnnotations(f.invisibleTypeAnnotations)
    }

    if (n.recordComponents != null) {
      n.recordComponents.forEach { r =>
        sortAnnotations(r.visibleAnnotations)
        sortAnnotations(r.invisibleAnnotations)
        sortTypeAnnotations(r.visibleTypeAnnotations)
        sortTypeAnnotations(r.invisibleTypeAnnotations)
      }
    }

    if (n.nestMembers != null) {
      Collections.sort(n.nestMembers)
    }
  }

  private def sortParameterAnnotations(parameters: Array[List[AnnotationNode]]): Unit = {
    if (parameters == null) {
      return
    }
    parameters.foreach(sortAnnotations)
  }

  private def sortTypeAnnotations(annos: List[TypeAnnotationNode]): Unit = {
    if (annos == null) {
      return
    }
    annos.sort(
      Comparator
        .comparing((a: TypeAnnotationNode) => a.desc)
        .thenComparing(a => String.valueOf(a.`typeRef`))
        .thenComparing(a => String.valueOf(a.typePath))
        .thenComparing(a => String.valueOf(a.values))
    )
  }

  private def sortAnnotations(annos: List[AnnotationNode]): Unit = {
    if (annos == null) {
      return
    }
    annos.sort(
      Comparator
        .comparing((a: AnnotationNode) => a.desc)
        .thenComparing(a => String.valueOf(a.values))
    )
  }

  private def removeUnusedInnerClassAttributes(
      infos: java.util.Map[String, InnerClassNode],
      n: ClassNode,
  ): Unit = {
    val types = new HashSet[String]()
    types.add(n.name)
    collectTypesFromSignature(types, n.signature)
    if (n.superName != null) {
      types.add(n.superName)
    }
    types.addAll(n.interfaces)

    addTypesInAnnotations(types, n.visibleAnnotations)
    addTypesInAnnotations(types, n.invisibleAnnotations)
    addTypesInTypeAnnotations(types, n.visibleTypeAnnotations)
    addTypesInTypeAnnotations(types, n.invisibleTypeAnnotations)

    n.methods.forEach { m =>
      collectTypesFromSignature(types, m.desc)
      collectTypesFromSignature(types, m.signature)
      types.addAll(m.exceptions)

      addTypesInAnnotations(types, m.visibleAnnotations)
      addTypesInAnnotations(types, m.invisibleAnnotations)
      addTypesInTypeAnnotations(types, m.visibleTypeAnnotations)
      addTypesInTypeAnnotations(types, m.invisibleTypeAnnotations)

      addTypesFromParameterAnnotations(types, m.visibleParameterAnnotations)
      addTypesFromParameterAnnotations(types, m.invisibleParameterAnnotations)

      collectTypesFromAnnotationValue(types, m.annotationDefault)
    }

    n.fields.forEach { f =>
      collectTypesFromSignature(types, f.desc)
      collectTypesFromSignature(types, f.signature)

      addTypesInAnnotations(types, f.visibleAnnotations)
      addTypesInAnnotations(types, f.invisibleAnnotations)
      addTypesInTypeAnnotations(types, f.visibleTypeAnnotations)
      addTypesInTypeAnnotations(types, f.invisibleTypeAnnotations)
    }

    if (n.recordComponents != null) {
      n.recordComponents.forEach { r =>
        collectTypesFromSignature(types, r.descriptor)
        collectTypesFromSignature(types, r.signature)

        addTypesInAnnotations(types, r.visibleAnnotations)
        addTypesInAnnotations(types, r.invisibleAnnotations)
        addTypesInTypeAnnotations(types, r.visibleTypeAnnotations)
        addTypesInTypeAnnotations(types, r.invisibleTypeAnnotations)
      }
    }

    if (n.nestMembers != null) {
      n.nestMembers.forEach { member =>
        val i = infos.get(member)
        if (i != null && i.outerName != null) {
          types.add(member)
        }
      }
    }

    val used = new ArrayList[InnerClassNode]()
    n.innerClasses.forEach { i =>
      if (i.outerName != null && i.outerName == n.name) {
        used.add(i)
      } else if (types.contains(i.name)) {
        addInnerChain(infos, used, i.name)
      }
    }
    addInnerChain(infos, used, n.name)
    n.innerClasses = used

    if (n.nestMembers != null) {
      val members = used.asScala.map(_.name).toSet
      n.nestMembers = n.nestMembers.asScala.filter(members.contains).asJava
    }
  }

  private def addTypesFromParameterAnnotations(
      types: Set[String],
      parameterAnnotations: Array[List[AnnotationNode]],
  ): Unit = {
    if (parameterAnnotations == null) {
      return
    }
    parameterAnnotations.foreach(annos => addTypesInAnnotations(types, annos))
  }

  private def addTypesInTypeAnnotations(types: Set[String], annos: List[TypeAnnotationNode]): Unit = {
    if (annos == null) {
      return
    }
    annos.forEach(a => collectTypesFromAnnotation(types, a))
  }

  private def addTypesInAnnotations(types: Set[String], annos: List[AnnotationNode]): Unit = {
    if (annos == null) {
      return
    }
    annos.forEach(a => collectTypesFromAnnotation(types, a))
  }

  private def collectTypesFromAnnotation(types: Set[String], a: AnnotationNode): Unit = {
    collectTypesFromSignature(types, a.desc)
    collectTypesFromAnnotationValues(types, a.values)
  }

  private def collectTypesFromAnnotationValues(types: Set[String], values: List[_]): Unit = {
    if (values == null) {
      return
    }
    values.forEach(v => collectTypesFromAnnotationValue(types, v))
  }

  private def collectTypesFromAnnotationValue(types: Set[String], v: Any): Unit = {
    v match {
      case list: java.util.List[_] =>
        collectTypesFromAnnotationValues(types, list)
      case t: Type =>
        collectTypesFromSignature(types, t.getDescriptor)
      case annotationNode: AnnotationNode =>
        collectTypesFromAnnotation(types, annotationNode)
      case enumValue: Array[String] =>
        collectTypesFromSignature(types, enumValue(0))
      case _ =>
    }
  }

  private def addInnerChain(
      infos: java.util.Map[String, InnerClassNode],
      used: List[InnerClassNode],
      name: String,
  ): Unit = {
    var current = name
    while (infos.containsKey(current)) {
      val info = infos.get(current)
      used.add(info)
      current = info.outerName
    }
  }

  private def collectTypesFromSignature(classes: Set[String], signature: String): Unit = {
    if (signature == null) {
      return
    }
    val classesRef = classes
    new SignatureReader(signature).accept(
      new SignatureVisitor(Opcodes.ASM9) {
        private val pieces = new ArrayDeque[List[String]]()

        override def visitInnerClassType(name: String): Unit = {
          pieces.getFirst.add(name)
        }

        override def visitClassType(name: String): Unit = {
          val classType = new ArrayList[String]()
          classType.add(name)
          pieces.push(classType)
        }

        override def visitEnd(): Unit = {
          classesRef.add(Joiner.on('$').join(pieces.pop()))
          super.visitEnd()
        }
      }
    )
  }
}
