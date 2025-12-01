/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.binder;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.ImportDecl;
import com.google.turbine.tree.Tree.ModDecl;
import com.google.turbine.tree.Tree.PkgDecl;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.tree.TurbineModifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Processes compilation units before binding, creating symbols for type declarations and desugaring
 * access modifiers.
 */
public final class CompUnitPreprocessor {

  /** A pre-processed compilation unit. */
  public static class PreprocessedCompUnit {
    private final ImmutableList<Tree.ImportDecl> imports;
    private final ImmutableList<SourceBoundClass> types;
    private final Optional<ModDecl> module;
    private final SourceFile source;
    private final String packageName;

    public PreprocessedCompUnit(
        ImmutableList<ImportDecl> imports,
        ImmutableList<SourceBoundClass> types,
        Optional<ModDecl> module,
        SourceFile source,
        String packageName) {
      this.imports = imports;
      this.types = types;
      this.module = module;
      this.source = source;
      this.packageName = packageName;
    }

    public ImmutableList<ImportDecl> imports() {
      return imports;
    }

    public ImmutableList<SourceBoundClass> types() {
      return types;
    }

    Optional<ModDecl> module() {
      return module;
    }

    public SourceFile source() {
      return source;
    }

    public String packageName() {
      return packageName;
    }
  }

  public static ImmutableList<PreprocessedCompUnit> preprocess(List<CompUnit> units) {
    ImmutableList.Builder<PreprocessedCompUnit> result = ImmutableList.builder();
    for (CompUnit unit : units) {
      result.add(preprocess(unit));
    }
    return result.build();
  }

  public static PreprocessedCompUnit preprocess(CompUnit unit) {
    String packageName;
    Iterable<TyDecl> decls = unit.decls();
    if (unit.pkg().isPresent()) {
      packageName = Joiner.on('/').join(unit.pkg().get().name());
      // "While the file could technically contain the source code
      // for one or more package-private (default-access) classes,
      // it would be very bad form." -- JLS 7.4.1
      if (isPackageInfo(unit)) {
        decls = Iterables.concat(decls, ImmutableList.of(packageInfoTree(unit.pkg().get())));
      }
    } else {
      packageName = "";
    }
    ImmutableList.Builder<SourceBoundClass> types = ImmutableList.builder();
    for (TyDecl decl : decls) {
      ClassSymbol sym =
          new ClassSymbol((!packageName.isEmpty() ? packageName + "/" : "") + decl.name());
      int access = access(decl.mods(), decl);
      ImmutableMap<String, ClassSymbol> children =
          preprocessChildren(unit.source(), types, sym, decl.members(), access);
      types.add(new SourceBoundClass(sym, null, children, access, decl));
    }
    return new PreprocessedCompUnit(
        unit.imports(), types.build(), unit.mod(), unit.source(), packageName);
  }

  private static boolean isPackageInfo(CompUnit unit) {
    String path = unit.source().path();
    if (path == null) {
      return false;
    }
    Path fileName = Paths.get(path).getFileName();
    if (fileName == null) {
      return false;
    }
    return fileName.toString().equals("package-info.java");
  }

  private static ImmutableMap<String, ClassSymbol> preprocessChildren(
      SourceFile source,
      ImmutableList.Builder<SourceBoundClass> types,
      ClassSymbol owner,
      ImmutableList<Tree> members,
      int enclosing) {
    ImmutableMap.Builder<String, ClassSymbol> result = ImmutableMap.builder();
    Set<String> seen = new HashSet<>();
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.TY_DECL) {
        Tree.TyDecl decl = (Tree.TyDecl) member;
        ClassSymbol sym = new ClassSymbol(owner.binaryName() + '$' + decl.name());
        if (!seen.add(decl.name().value())) {
          // TURBINE-DIFF START
          continue;
          // throw TurbineError.format(
          //     source, member.position(), ErrorKind.DUPLICATE_DECLARATION, sym);
          // TURBINE-DIFF END
        }
        result.put(decl.name().value(), sym);

        int access = innerClassAccess(enclosing, decl);

        ImmutableMap<String, ClassSymbol> children =
            preprocessChildren(source, types, sym, decl.members(), access);
        types.add(new SourceBoundClass(sym, owner, children, access, decl));
      }
    }
    return result.buildOrThrow();
  }

  /** Desugars access flags for a class. */
  public static int access(ImmutableSet<TurbineModifier> mods, TyDecl decl) {
    int access = 0;
    for (TurbineModifier m : mods) {
      access |= m.flag();
    }
    switch (decl.tykind()) {
      case CLASS:
        access |= TurbineFlag.ACC_SUPER;
        break;
      case INTERFACE:
        access |= TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_INTERFACE;
        break;
      case ENUM:
        // Assuming all enums are non-abstract is safe, because nothing outside
        // the compilation unit can extend abstract enums, and refactoring an
        // existing enum to implement methods in the container class instead
        // of the constants is not a breaking change.
        access |= TurbineFlag.ACC_SUPER | TurbineFlag.ACC_ENUM;
        if (isEnumFinal(decl.members())) {
          access |= TurbineFlag.ACC_FINAL;
        }
        break;
      case ANNOTATION:
        access |= TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_INTERFACE | TurbineFlag.ACC_ANNOTATION;
        break;
      case RECORD:
        access |= TurbineFlag.ACC_SUPER | TurbineFlag.ACC_FINAL;
        break;
    }
    return access;
  }

  /**
   * If any enum constants have a class body (which is recorded in the parser by setting ENUM_IMPL),
   * the class generated for the enum needs to not have ACC_FINAL set.
   */
  private static boolean isEnumFinal(ImmutableList<Tree> declMembers) {
    for (Tree t : declMembers) {
      if (t.kind() != Tree.Kind.VAR_DECL) {
        continue;
      }
      Tree.VarDecl var = (Tree.VarDecl) t;
      if (!var.mods().contains(TurbineModifier.ENUM_IMPL)) {
        continue;
      }
      return false;
    }
    return true;
  }

  /** Desugars access flags for an inner class. */
  private static int innerClassAccess(int enclosing, TyDecl decl) {
    int access = access(decl.mods(), decl);

    // types declared in interfaces and annotations are implicitly public (JLS 9.5)
    if ((enclosing & (TurbineFlag.ACC_INTERFACE | TurbineFlag.ACC_ANNOTATION)) != 0) {
      access &= ~(TurbineFlag.ACC_PRIVATE | TurbineFlag.ACC_PROTECTED);
      access |= TurbineFlag.ACC_PUBLIC;
    }

    // Nested enums, interfaces, and annotations, and any types nested within interfaces and
    // annotations (JLS 9.5) are implicitly static.
    switch (decl.tykind()) {
      case INTERFACE:
      case ENUM:
      case ANNOTATION:
      case RECORD:
        access |= TurbineFlag.ACC_STATIC;
        break;
      case CLASS:
        if ((enclosing & (TurbineFlag.ACC_INTERFACE | TurbineFlag.ACC_ANNOTATION)) != 0) {
          access |= TurbineFlag.ACC_STATIC;
        }
        break;
    }

    // propagate strictfp to nested types
    access |= (enclosing & TurbineFlag.ACC_STRICT);
    return access;
  }

  /** package-info.java's are desugared into synthetic class declarations. */
  private static TyDecl packageInfoTree(PkgDecl pkgDecl) {
    return new TyDecl(
        pkgDecl.position(),
        ImmutableSet.of(TurbineModifier.ACC_SYNTHETIC),
        pkgDecl.annos(),
        new Ident(pkgDecl.position(), "package-info"),
        ImmutableList.of(),
        Optional.empty(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        TurbineTyKind.INTERFACE,
        /* javadoc= */ null);
  }

  private CompUnitPreprocessor() {}
}
