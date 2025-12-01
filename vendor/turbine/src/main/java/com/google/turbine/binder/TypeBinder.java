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

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.RecordComponentSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Anno;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.Kind;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.TurbineModifier;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.types.Deannotate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Type binding. */
public class TypeBinder {

  /** A scope containing a single {@link Symbol}. */
  private static class SingletonScope implements Scope {

    private final String name;
    private final Symbol sym;

    public SingletonScope(String name, Symbol sym) {
      this.name = name;
      this.sym = sym;
    }

    @Override
    public @Nullable LookupResult lookup(LookupKey lookup) {
      if (name.equals(lookup.first().value())) {
        return new LookupResult(sym, lookup);
      }
      return null;
    }
  }

  /** A scope backed by a map of simple names to {@link Symbol}s. */
  private static class MapScope implements Scope {
    private final ImmutableMap<String, ? extends Symbol> tps;

    public MapScope(ImmutableMap<String, ? extends Symbol> tps) {
      this.tps = tps;
    }

    @Override
    public @Nullable LookupResult lookup(LookupKey lookupKey) {
      Symbol sym = tps.get(lookupKey.first().value());
      return sym != null ? new LookupResult(sym, lookupKey) : null;
    }
  }

  /**
   * A scope containing all symbols in lexically enclosing scopes of a class, including type
   * parameters, and declared and inherited members
   */
  private static class ClassMemberScope implements Scope {
    private final ClassSymbol sym;
    private final Env<ClassSymbol, HeaderBoundClass> env;

    public ClassMemberScope(ClassSymbol sym, Env<ClassSymbol, HeaderBoundClass> env) {
      this.sym = sym;
      this.env = env;
    }

    @Override
    public @Nullable LookupResult lookup(LookupKey lookup) {
      ClassSymbol curr = sym;
      while (curr != null) {
        Symbol result = Resolve.resolve(env, sym, curr, lookup.first());
        if (result != null) {
          return new LookupResult(result, lookup);
        }
        HeaderBoundClass info = env.getNonNull(curr);
        result = info.typeParameters().get(lookup.first().value());
        if (result != null) {
          return new LookupResult(result, lookup);
        }
        curr = info.owner();
      }
      return null;
    }
  }

  /** Creates {@link SourceTypeBoundClass} nodes for a compilation. */
  public static SourceTypeBoundClass bind(
      TurbineLogWithSource log,
      Env<ClassSymbol, HeaderBoundClass> env,
      ClassSymbol sym,
      SourceHeaderBoundClass base) {
    return new TypeBinder(log, env, sym, base).bind();
  }

  private final TurbineLogWithSource log;
  private final Env<ClassSymbol, HeaderBoundClass> env;
  private final ClassSymbol owner;
  private final SourceHeaderBoundClass base;

  private TypeBinder(
      TurbineLogWithSource log,
      Env<ClassSymbol, HeaderBoundClass> env,
      ClassSymbol owner,
      SourceHeaderBoundClass base) {
    this.log = log;
    this.env = env;
    this.owner = owner;
    this.base = base;
  }

  private SourceTypeBoundClass bind() {
    // This method uses two scopes. This first one is built up as we process the signature
    // and its elements become visible to subsequent elements (e.g. type parameters can
    // refer to previous declared type parameters, the superclass type can refer to
    // type parameters, ...). A second scope is created for finding methods and fields
    // once the signature is fully determined.
    CompoundScope enclosingScope =
        base.scope()
            .toScope(Resolve.resolveFunction(env, owner))
            .append(new SingletonScope(base.decl().name().value(), owner));
    if (base.owner() != null) {
      enclosingScope = enclosingScope.append(new ClassMemberScope(base.owner(), env));
    }

    ImmutableList<AnnoInfo> annotations = bindAnnotations(enclosingScope, base.decl().annos());

    CompoundScope bindingScope = enclosingScope;

    // type parameters can refer to each other in f-bounds, so update the scope first
    bindingScope = bindingScope.append(new MapScope(base.typeParameters()));
    final ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes =
        bindTyParams(base.decl().typarams(), bindingScope, base.typeParameters());

    ImmutableList.Builder<Type> interfaceTypes = ImmutableList.builder();
    Type superClassType;
    switch (base.kind()) {
      case ENUM:
        superClassType =
            Type.ClassTy.create(
                ImmutableList.of(
                    Type.ClassTy.SimpleClassTy.create(
                        ClassSymbol.ENUM,
                        ImmutableList.of(Type.ClassTy.asNonParametricClassTy(owner)),
                        ImmutableList.of())));
        break;
      case ANNOTATION:
        superClassType = Type.ClassTy.OBJECT;
        interfaceTypes.add(Type.ClassTy.asNonParametricClassTy(ClassSymbol.ANNOTATION));
        break;
      case CLASS:
        if (base.decl().xtnds().isPresent()) {
          superClassType =
              checkClassType(bindingScope, base.decl().xtnds().get(), /* expectInterface= */ false);
        } else if (owner.equals(ClassSymbol.OBJECT)) {
          // java.lang.Object doesn't have a superclass
          superClassType = null;
        } else {
          superClassType = Type.ClassTy.OBJECT;
        }
        break;
      case INTERFACE:
        if (base.decl().xtnds().isPresent()) {
          throw new AssertionError();
        }
        superClassType = Type.ClassTy.OBJECT;
        break;
      case RECORD:
        superClassType = Type.ClassTy.asNonParametricClassTy(ClassSymbol.RECORD);
        break;
      default:
        throw new AssertionError(base.decl().tykind());
    }

    for (Tree.ClassTy i : base.decl().impls()) {
      interfaceTypes.add(checkClassType(bindingScope, i, /* expectInterface= */ true));
    }

    ImmutableList.Builder<ClassSymbol> permits = ImmutableList.builder();
    for (Tree.ClassTy i : base.decl().permits()) {
      Type type = bindClassTy(bindingScope, i);
      switch (type.tyKind()) {
        case ERROR_TY:
          continue;
        case CLASS_TY:
          permits.add(((Type.ClassTy) type).sym());
          break;
        default:
          throw new AssertionError(type.tyKind());
      }
    }

    CompoundScope scope =
        base.scope()
            .toScope(Resolve.resolveFunction(env, owner))
            .append(new SingletonScope(base.decl().name().value(), owner))
            .append(new ClassMemberScope(owner, env));

    SyntheticMethods syntheticMethods = new SyntheticMethods();

    ImmutableList<RecordComponentInfo> components = bindComponents(scope, base.decl().components());

    List<MethodInfo> boundMethods = bindMethods(scope, base.decl().members(), components);
    ImmutableList<MethodInfo> methods;
    if (base.kind().equals(TurbineTyKind.RECORD)) {
      methods = recordMethods(syntheticMethods, components, boundMethods);
    } else {
      methods =
          ImmutableList.<MethodInfo>builder()
              .addAll(syntheticMethods(syntheticMethods))
              .addAll(boundMethods)
              .build();
    }

    ImmutableList<FieldInfo> fields = bindFields(scope, base.decl().members());

    return new SourceTypeBoundClass(
        interfaceTypes.build(),
        permits.build(),
        superClassType,
        typeParameterTypes,
        base.access(),
        components,
        methods,
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.typeParameters(),
        enclosingScope,
        scope,
        base.memberImports(),
        /* annotation metadata */ null,
        annotations,
        base.source(),
        base.decl());
  }

  private ImmutableList<MethodInfo> recordMethods(
      SyntheticMethods syntheticMethods,
      ImmutableList<RecordComponentInfo> components,
      List<MethodInfo> boundMethods) {
    List<MethodInfo> boundConstructors = new ArrayList<>();
    List<MethodInfo> boundNonConstructors = new ArrayList<>();
    boolean hasToString = false;
    boolean hasEquals = false;
    boolean hasHashCode = false;
    boolean hasPrimaryConstructor = false;
    Set<String> componentNamesToDeclare = new HashSet<>();
    for (RecordComponentInfo c : components) {
      componentNamesToDeclare.add(c.name());
    }
    for (MethodInfo m : boundMethods) {
      if (m.name().equals("<init>")) {
        if (isPrimaryConstructor(m, components)) {
          hasPrimaryConstructor = true;
        }
        boundConstructors.add(m);
      } else {
        switch (m.name()) {
          case "toString":
            hasToString = m.parameters().isEmpty();
            break;
          case "equals":
            hasEquals =
                m.parameters().size() == 1
                    && hasSameErasure(getOnlyElement(m.parameters()).type(), Type.ClassTy.OBJECT);
            break;
          case "hashCode":
            hasHashCode = m.parameters().isEmpty();
            break;
          default:
            if (m.parameters().isEmpty()) {
              componentNamesToDeclare.remove(m.name());
            }
        }
        boundNonConstructors.add(m);
      }
    }
    ImmutableList.Builder<MethodInfo> methods = ImmutableList.builder();
    methods.addAll(boundConstructors);
    if (!hasPrimaryConstructor) {
      methods.add(defaultRecordConstructor(syntheticMethods, components));
    }
    methods.addAll(boundNonConstructors);
    if (!hasToString) {
      MethodSymbol toStringMethod = syntheticMethods.create(owner, "toString");
      methods.add(
          new MethodInfo(
              toStringMethod,
              ImmutableMap.of(),
              Type.ClassTy.STRING,
              ImmutableList.of(),
              ImmutableList.of(),
              TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL,
              null,
              null,
              ImmutableList.of(),
              null));
    }
    if (!hasHashCode) {
      MethodSymbol hashCodeMethod = syntheticMethods.create(owner, "hashCode");
      methods.add(
          new MethodInfo(
              hashCodeMethod,
              ImmutableMap.of(),
              Type.PrimTy.create(TurbineConstantTypeKind.INT, ImmutableList.of()),
              ImmutableList.of(),
              ImmutableList.of(),
              TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL,
              null,
              null,
              ImmutableList.of(),
              null));
    }
    if (!hasEquals) {
      MethodSymbol equalsMethod = syntheticMethods.create(owner, "equals");
      methods.add(
          new MethodInfo(
              equalsMethod,
              ImmutableMap.of(),
              Type.PrimTy.create(TurbineConstantTypeKind.BOOLEAN, ImmutableList.of()),
              ImmutableList.of(
                  new ParamInfo(
                      new ParamSymbol(equalsMethod, "other"),
                      Type.ClassTy.OBJECT,
                      ImmutableList.of(),
                      TurbineFlag.ACC_MANDATED)),
              ImmutableList.of(),
              TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL,
              null,
              null,
              ImmutableList.of(),
              null));
    }
    for (RecordComponentInfo c : components) {
      if (!componentNamesToDeclare.contains(c.name())) {
        continue;
      }
      MethodSymbol componentMethod = syntheticMethods.create(owner, c.name());
      methods.add(
          new MethodInfo(
              componentMethod,
              ImmutableMap.of(),
              c.type(),
              ImmutableList.of(),
              ImmutableList.of(),
              TurbineFlag.ACC_PUBLIC,
              null,
              null,
              c.annotations(),
              null));
    }
    return methods.build();
  }

  private MethodInfo defaultRecordConstructor(
      SyntheticMethods syntheticMethods, ImmutableList<RecordComponentInfo> components) {
    MethodSymbol symbol = syntheticMethods.create(owner, "<init>");
    ImmutableList.Builder<ParamInfo> params = ImmutableList.builder();
    for (RecordComponentInfo component : components) {
      params.add(
          new ParamInfo(
              new ParamSymbol(symbol, component.name()),
              component.type(),
              component.annotations(),
              component.access()));
    }
    return syntheticConstructor(
        symbol, params.build(), TurbineVisibility.fromAccess(base.access()));
  }

  private boolean isPrimaryConstructor(
      MethodInfo m, ImmutableList<RecordComponentInfo> components) {
    if (m.parameters().size() != components.size()) {
      return false;
    }
    for (int i = 0; i < m.parameters().size(); i++) {
      if (!hasSameErasure(m.parameters().get(i).type(), components.get(i).type())) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasSameErasure(Type a, Type b) {
    switch (a.tyKind()) {
      case PRIM_TY:
        return b.tyKind() == Type.TyKind.PRIM_TY
            && ((Type.PrimTy) a).primkind() == ((Type.PrimTy) b).primkind();
      case CLASS_TY:
        return b.tyKind() == Type.TyKind.CLASS_TY
            && ((Type.ClassTy) a).sym().equals(((Type.ClassTy) b).sym());
      case ARRAY_TY:
        return b.tyKind() == Type.TyKind.ARRAY_TY
            && hasSameErasure(((Type.ArrayTy) a).elementType(), ((Type.ArrayTy) b).elementType());
      case TY_VAR:
        return b.tyKind() == Type.TyKind.TY_VAR
            && ((Type.TyVar) a).sym().equals(((Type.TyVar) b).sym());
      case ERROR_TY:
        return false;
      case WILD_TY:
      case INTERSECTION_TY:
      case METHOD_TY:
      case NONE_TY:
      case VOID_TY:
        // fall out: impossible method parameter types
    }
    throw new AssertionError(a.tyKind());
  }

  private Type checkClassType(CompoundScope scope, ClassTy tree, boolean expectInterface) {
    Type type = bindClassTy(scope, tree);
    if (type.tyKind().equals(Type.TyKind.ERROR_TY)) {
      return type;
    }
    // TURBINE-DIFF START
    if (type instanceof Type.TyVar) {
      return type;
    }
    // TURBINE-DIFF END
    HeaderBoundClass info = env.getNonNull(((Type.ClassTy) type).sym());
    boolean isInterface;
    switch (info.kind()) {
      case INTERFACE:
      case ANNOTATION:
        isInterface = true;
        break;
      default:
        isInterface = false;
        break;
    }
    if (expectInterface != isInterface) {
      log.error(
          tree.position(),
          expectInterface ? ErrorKind.EXPECTED_INTERFACE : ErrorKind.UNEXPECTED_INTERFACE);
    }
    return type;
  }

  /**
   * A generated for synthetic {@link MethodSymbol}s.
   *
   * <p>Each {@link MethodSymbol} contains an index into its enclosing class, to enable comparing
   * the symbols for equality. For synthetic methods we use an arbitrary unique negative index.
   */
  private static class SyntheticMethods {

    private int idx = -1;

    MethodSymbol create(ClassSymbol owner, String name) {
      return new MethodSymbol(idx--, owner, name);
    }
  }

  private ImmutableList<RecordComponentInfo> bindComponents(
      CompoundScope scope, ImmutableList<Tree.VarDecl> components) {
    ImmutableList.Builder<RecordComponentInfo> result = ImmutableList.builder();
    for (Tree.VarDecl p : components) {
      int access = 0;
      for (TurbineModifier m : p.mods()) {
        access |= m.flag();
      }
      RecordComponentInfo param =
          new RecordComponentInfo(
              new RecordComponentSymbol(owner, p.name().value()),
              bindTy(scope, p.ty()),
              bindAnnotations(scope, p.annos()),
              access);
      result.add(param);
    }
    return result.build();
  }

  /** Collect synthetic and implicit methods, including default constructors and enum methods. */
  ImmutableList<MethodInfo> syntheticMethods(SyntheticMethods syntheticMethods) {
    switch (base.kind()) {
      case CLASS:
        return maybeDefaultConstructor(syntheticMethods);
      case ENUM:
        return syntheticEnumMethods(syntheticMethods);
      default:
        return ImmutableList.of();
    }
  }

  private ImmutableList<MethodInfo> maybeDefaultConstructor(SyntheticMethods syntheticMethods) {
    if (hasConstructor()) {
      return ImmutableList.of();
    }
    MethodSymbol symbol = syntheticMethods.create(owner, "<init>");
    ImmutableList<ParamInfo> formals;
    if (hasEnclosingInstance(base)) {
      formals = ImmutableList.of(enclosingInstanceParameter(symbol));
    } else {
      formals = ImmutableList.of();
    }
    return ImmutableList.of(
        syntheticConstructor(symbol, formals, TurbineVisibility.fromAccess(base.access())));
  }

  private MethodInfo syntheticConstructor(
      MethodSymbol symbol, ImmutableList<ParamInfo> formals, TurbineVisibility visibility) {
    int access = visibility.flag();
    access |= (base.access() & TurbineFlag.ACC_STRICT);
    if (!formals.isEmpty()
        && (getLast(formals).access() & TurbineFlag.ACC_VARARGS) == TurbineFlag.ACC_VARARGS) {
      access |= TurbineFlag.ACC_VARARGS;
    }
    return new MethodInfo(
        symbol,
        ImmutableMap.of(),
        Type.VOID,
        formals,
        ImmutableList.of(),
        access | TurbineFlag.ACC_SYNTH_CTOR,
        null,
        null,
        ImmutableList.of(),
        null);
  }

  private ParamInfo enclosingInstanceParameter(MethodSymbol owner) {
    int access = TurbineFlag.ACC_FINAL;
    if ((base.access() & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
      access |= TurbineFlag.ACC_SYNTHETIC;
    } else {
      access |= TurbineFlag.ACC_MANDATED;
    }
    int enclosingInstances = 0;
    for (ClassSymbol sym = base.owner(); sym != null; ) {
      HeaderBoundClass info = env.getNonNull(sym);
      if (((info.access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC)
          || info.owner() == null) {
        break;
      }
      enclosingInstances++;
      sym = info.owner();
    }
    return new ParamInfo(
        new ParamSymbol(owner, "this$" + enclosingInstances),
        Type.ClassTy.asNonParametricClassTy(base.owner()),
        ImmutableList.of(),
        access);
  }

  private static ImmutableList<ParamInfo> enumCtorParams(MethodSymbol owner) {
    return ImmutableList.of(
        new ParamInfo(
            new ParamSymbol(owner, "$enum$name"),
            Type.ClassTy.STRING,
            ImmutableList.of(),
            /*synthetic*/
            TurbineFlag.ACC_SYNTHETIC),
        new ParamInfo(
            new ParamSymbol(owner, "$enum$ordinal"),
            Type.PrimTy.create(TurbineConstantTypeKind.INT, ImmutableList.of()),
            ImmutableList.of(),
            /*synthetic*/
            TurbineFlag.ACC_SYNTHETIC));
  }

  private ImmutableList<MethodInfo> syntheticEnumMethods(SyntheticMethods syntheticMethods) {
    ImmutableList.Builder<MethodInfo> methods = ImmutableList.builder();
    int access = 0;
    access |= (base.access() & TurbineFlag.ACC_STRICT);
    if (!hasConstructor()) {
      MethodSymbol symbol = syntheticMethods.create(owner, "<init>");
      methods.add(syntheticConstructor(symbol, enumCtorParams(symbol), TurbineVisibility.PRIVATE));
    }
    MethodSymbol valuesMethod = syntheticMethods.create(owner, "values");
    methods.add(
        new MethodInfo(
            valuesMethod,
            ImmutableMap.of(),
            Type.ArrayTy.create(Type.ClassTy.asNonParametricClassTy(owner), ImmutableList.of()),
            ImmutableList.of(),
            ImmutableList.of(),
            access | TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
            null,
            null,
            ImmutableList.of(),
            null));
    MethodSymbol valueOfMethod = syntheticMethods.create(owner, "valueOf");
    methods.add(
        new MethodInfo(
            valueOfMethod,
            ImmutableMap.of(),
            Type.ClassTy.asNonParametricClassTy(owner),
            ImmutableList.of(
                new ParamInfo(
                    new ParamSymbol(valueOfMethod, "name"),
                    Type.ClassTy.STRING,
                    ImmutableList.of(),
                    TurbineFlag.ACC_MANDATED)),
            ImmutableList.of(),
            access | TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
            null,
            null,
            ImmutableList.of(),
            null));
    return methods.build();
  }

  private boolean hasConstructor() {
    for (Tree m : base.decl().members()) {
      if (m.kind() != Kind.METH_DECL) {
        continue;
      }
      if (((MethDecl) m).name().value().equals("<init>")) {
        return true;
      }
    }
    return false;
  }

  /** Bind type parameter types. */
  private ImmutableMap<TyVarSymbol, TyVarInfo> bindTyParams(
      ImmutableList<Tree.TyParam> trees, CompoundScope scope, Map<String, TyVarSymbol> symbols) {
    LinkedHashMap<TyVarSymbol, TyVarInfo> result = new LinkedHashMap<>();
    for (Tree.TyParam tree : trees) {
      // `symbols` is constructed to guarantee the requireNonNull call is safe.
      TyVarSymbol sym = requireNonNull(symbols.get(tree.name().value()));
      ImmutableList.Builder<Type> bounds = ImmutableList.builder();
      for (Tree bound : tree.bounds()) {
        bounds.add(bindTy(scope, bound));
      }
      ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, tree.annos());
      TyVarInfo existing =
          result.putIfAbsent(
              sym,
              new TyVarInfo(
                  IntersectionTy.create(bounds.build()), /* lowerBound= */ null, annotations));
      if (existing != null) {
        log.error(tree.position(), ErrorKind.DUPLICATE_DECLARATION, tree.name());
      }
    }
    return ImmutableMap.copyOf(result);
  }

  private List<MethodInfo> bindMethods(
      CompoundScope scope,
      ImmutableList<Tree> members,
      ImmutableList<RecordComponentInfo> components) {
    List<MethodInfo> methods = new ArrayList<>();
    int idx = 0;
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.METH_DECL) {
        methods.add(bindMethod(idx++, scope, (MethDecl) member, components));
      }
    }
    return methods;
  }

  private MethodInfo bindMethod(
      int idx, CompoundScope scope, MethDecl t, ImmutableList<RecordComponentInfo> components) {

    MethodSymbol sym = new MethodSymbol(idx, owner, t.name().value());

    ImmutableMap<String, TyVarSymbol> typeParameters;
    {
      ImmutableMap.Builder<String, TyVarSymbol> builder = ImmutableMap.builder();
      for (Tree.TyParam pt : t.typarams()) {
        builder.put(pt.name().value(), new TyVarSymbol(sym, pt.name().value()));
      }
      // errors for duplicates are reported in bindTyParams
      typeParameters = builder.buildKeepingLast();
    }

    // type parameters can refer to each other in f-bounds, so update the scope first
    scope = scope.append(new MapScope(typeParameters));
    ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes =
        bindTyParams(t.typarams(), scope, typeParameters);

    Type returnType;
    if (t.ret().isPresent()) {
      returnType = bindTy(scope, t.ret().get());
    } else {
      returnType = Type.VOID;
    }

    ImmutableList.Builder<ParamInfo> parameters = ImmutableList.builder();
    String name = t.name().value();
    if (name.equals("<init>")) {
      if (hasEnclosingInstance(base)) {
        parameters.add(enclosingInstanceParameter(sym));
      } else {
        switch (base.kind()) {
          case ENUM:
            parameters.addAll(enumCtorParams(sym));
            break;
          case RECORD:
            if (t.mods().contains(TurbineModifier.COMPACT_CTOR)) {
              for (RecordComponentInfo component : components) {
                parameters.add(
                    new ParamInfo(
                        new ParamSymbol(sym, component.name()),
                        component.type(),
                        component.annotations(),
                        component.access()));
              }
            }
            break;
          default:
            break;
        }
      }
    }
    ParamInfo receiver = null;
    for (Tree.VarDecl p : t.params()) {
      int access = 0;
      for (TurbineModifier m : p.mods()) {
        access |= m.flag();
      }
      ParamInfo param =
          new ParamInfo(
              new ParamSymbol(sym, p.name().value()),
              bindTy(scope, p.ty()),
              bindAnnotations(scope, p.annos()), /*synthetic*/
              access);
      if (p.name().value().equals("this")) {
        receiver = param;
        continue;
      }
      parameters.add(param);
    }
    ImmutableList.Builder<Type> exceptions = ImmutableList.builder();
    for (Tree.ClassTy p : t.exntys()) {
      exceptions.add(bindClassTy(scope, p));
    }

    int access = 0;
    for (TurbineModifier m : t.mods()) {
      access |= m.flag();
    }

    switch (base.kind()) {
      case INTERFACE:
      case ANNOTATION:
        // interface members have default public visibility
        if ((access & TurbineVisibility.VISIBILITY_MASK) == 0) {
          access |= TurbineFlag.ACC_PUBLIC;
        }
        if ((access
                & (TurbineFlag.ACC_DEFAULT | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_SYNTHETIC))
            == 0) {
          access |= TurbineFlag.ACC_ABSTRACT;
        }
        if ((access & TurbineFlag.ACC_FINAL) == TurbineFlag.ACC_FINAL) {
          log.error(t.position(), ErrorKind.UNEXPECTED_MODIFIER, TurbineModifier.FINAL);
        }
        break;
      case ENUM:
        if (name.equals("<init>")) {
          access |= TurbineFlag.ACC_PRIVATE;
        }
        break;
      default:
        break;
    }

    if (((base.access() & TurbineFlag.ACC_STRICT) == TurbineFlag.ACC_STRICT)
        && (access & TurbineFlag.ACC_ABSTRACT) == 0) {
      access |= TurbineFlag.ACC_STRICT;
    }

    ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, t.annos());
    return new MethodInfo(
        sym,
        typeParameterTypes,
        returnType,
        parameters.build(),
        exceptions.build(),
        access,
        null,
        t,
        annotations,
        receiver);
  }

  private static boolean hasEnclosingInstance(HeaderBoundClass base) {
    return base.kind() == TurbineTyKind.CLASS
        && base.owner() != null
        && ((base.access() & TurbineFlag.ACC_STATIC) == 0);
  }

  private ImmutableList<FieldInfo> bindFields(CompoundScope scope, ImmutableList<Tree> members) {
    Set<FieldSymbol> seen = new HashSet<>();
    ImmutableList.Builder<FieldInfo> fields = ImmutableList.builder();
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.VAR_DECL) {
        FieldInfo field = bindField(scope, (Tree.VarDecl) member);
        if (!seen.add(field.sym())) {
          log.error(member.position(), ErrorKind.DUPLICATE_DECLARATION, "field: " + field.name());
          continue;
        }
        fields.add(field);
      }
    }
    return fields.build();
  }

  private FieldInfo bindField(CompoundScope scope, Tree.VarDecl decl) {
    FieldSymbol sym = new FieldSymbol(owner, decl.name().value());
    Type type = bindTy(scope, decl.ty());
    ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, decl.annos());
    int access = 0;
    for (TurbineModifier m : decl.mods()) {
      access |= m.flag();
    }
    switch (base.kind()) {
      case INTERFACE:
      case ANNOTATION:
        access |= TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_STATIC;
        break;
      default:
        break;
    }
    return new FieldInfo(sym, type, access, annotations, decl, null);
  }

  private ImmutableList<AnnoInfo> bindAnnotations(
      CompoundScope scope, ImmutableList<Tree.Anno> trees) {
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (Tree.Anno tree : trees) {
      ImmutableList<Ident> name = tree.name();
      LookupResult lookupResult = scope.lookup(new LookupKey(name));
      ClassSymbol sym = resolveAnnoSymbol(tree, name, lookupResult);
      result.add(new AnnoInfo(base.source(), sym, tree, ImmutableMap.of()));
    }
    return result.build();
  }

  private @Nullable ClassSymbol resolveAnnoSymbol(
      Anno tree, ImmutableList<Ident> name, @Nullable LookupResult lookupResult) {
    if (lookupResult == null) {
      log.error(tree.position(), ErrorKind.CANNOT_RESOLVE, Joiner.on('.').join(name));
      return null;
    }
    ClassSymbol sym = (ClassSymbol) lookupResult.sym();
    for (Ident ident : lookupResult.remaining()) {
      sym = resolveNext(sym, ident);
      if (sym == null) {
        return null;
      }
    }
    if (env.getNonNull(sym).kind() != TurbineTyKind.ANNOTATION) {
      log.error(tree.position(), ErrorKind.NOT_AN_ANNOTATION, sym);
    }
    return sym;
  }

  private @Nullable ClassSymbol resolveNext(ClassSymbol sym, Ident bit) {
    ClassSymbol next = Resolve.resolve(env, owner, sym, bit);
    if (next == null) {
      log.error(
          bit.position(),
          ErrorKind.SYMBOL_NOT_FOUND,
          new ClassSymbol(sym.binaryName() + '$' + bit));
    }
    return next;
  }

  private ImmutableList<Type> bindTyArgs(CompoundScope scope, ImmutableList<Tree.Type> targs) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Tree.Type ty : targs) {
      result.add(bindTyArg(scope, ty));
    }
    return result.build();
  }

  private Type bindTyArg(CompoundScope scope, Tree.Type ty) {
    switch (ty.kind()) {
      case WILD_TY:
        return bindWildTy(scope, (Tree.WildTy) ty);
      default:
        Type result = bindTy(scope, ty);
        if (result.tyKind().equals(Type.TyKind.PRIM_TY)) {
          // Omit type annotations when printing the type in the diagnostic, since they're
          // irrelevant and could be invalid if there were deferred errors.
          // TODO(cushon): consider ensuring this is done for all diagnostics that mention types
          log.error(ty.position(), ErrorKind.UNEXPECTED_TYPE, Deannotate.deannotate(result));
        }
        return result;
    }
  }

  private Type bindTy(CompoundScope scope, Tree t) {
    switch (t.kind()) {
      case CLASS_TY:
        return bindClassTy(scope, (Tree.ClassTy) t);
      case PRIM_TY:
        return bindPrimTy(scope, (Tree.PrimTy) t);
      case ARR_TY:
        return bindArrTy(scope, (Tree.ArrTy) t);
      case VOID_TY:
        return Type.VOID;
      default:
        throw new AssertionError(t.kind());
    }
  }

  private Type bindClassTy(CompoundScope scope, Tree.ClassTy t) {
    // flatten the components of a qualified class type
    ArrayList<Tree.ClassTy> flat;
    {
      ArrayDeque<Tree.ClassTy> builder = new ArrayDeque<>();
      for (Tree.ClassTy curr = t; curr != null; curr = curr.base().orElse(null)) {
        builder.addFirst(curr);
      }
      flat = new ArrayList<>(builder);
    }
    // the simple names of all classes in the qualified name
    ImmutableList.Builder<Tree.Ident> nameBuilder = ImmutableList.builder();
    for (Tree.ClassTy curr : flat) {
      nameBuilder.add(curr.name());
    }
    ImmutableList<Tree.Ident> names = nameBuilder.build();
    // resolve the prefix to a symbol
    LookupResult result = scope.lookup(new LookupKey(names));
    if (result == null || result.sym() == null) {
      log.error(names.get(0).position(), ErrorKind.CANNOT_RESOLVE, Joiner.on('.').join(names));
      return Type.ErrorTy.create(names, bindTyArgs(scope, t.tyargs()));
    }
    Symbol sym = result.sym();
    int annoIdx = flat.size() - result.remaining().size() - 1;
    ImmutableList<AnnoInfo> annos = bindAnnotations(scope, flat.get(annoIdx).annos());
    switch (sym.symKind()) {
      case CLASS:
        // resolve any remaining types in the qualified name, and their type arguments
        return bindClassTyRest(scope, flat, names, result, (ClassSymbol) sym, annos);
      case TY_PARAM:
        if (!result.remaining().isEmpty()) {
          log.error(t.position(), ErrorKind.TYPE_PARAMETER_QUALIFIER);
          return Type.ErrorTy.create(names, ImmutableList.of());
        }
        return Type.TyVar.create((TyVarSymbol) sym, annos);
      default:
        throw new AssertionError(sym.symKind());
    }
  }

  private Type bindClassTyRest(
      CompoundScope scope,
      ArrayList<ClassTy> flat,
      ImmutableList<Tree.Ident> bits,
      LookupResult result,
      ClassSymbol sym,
      ImmutableList<AnnoInfo> annotations) {
    int idx = bits.size() - result.remaining().size() - 1;
    ImmutableList.Builder<Type.ClassTy.SimpleClassTy> classes = ImmutableList.builder();
    classes.add(
        Type.ClassTy.SimpleClassTy.create(
            sym, bindTyArgs(scope, flat.get(idx++).tyargs()), annotations));
    for (; idx < flat.size(); idx++) {
      Tree.ClassTy curr = flat.get(idx);
      ClassSymbol next = resolveNext(sym, curr.name());
      ImmutableList<Type> targs = bindTyArgs(scope, curr.tyargs());
      if (next == null) {
        return Type.ErrorTy.create(bits, targs);
      }
      sym = next;

      annotations = bindAnnotations(scope, curr.annos());
      classes.add(Type.ClassTy.SimpleClassTy.create(sym, targs, annotations));
    }
    return Type.ClassTy.create(classes.build());
  }

  private Type.PrimTy bindPrimTy(CompoundScope scope, PrimTy t) {
    return Type.PrimTy.create(t.tykind(), bindAnnotations(scope, t.annos()));
  }

  private Type bindArrTy(CompoundScope scope, Tree.ArrTy t) {
    return Type.ArrayTy.create(bindTy(scope, t.elem()), bindAnnotations(scope, t.annos()));
  }

  private Type bindWildTy(CompoundScope scope, Tree.WildTy t) {
    ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, t.annos());
    if (t.lower().isPresent()) {
      return Type.WildLowerBoundedTy.create(bindTy(scope, t.lower().get()), annotations);
    } else if (t.upper().isPresent()) {
      return Type.WildUpperBoundedTy.create(bindTy(scope, t.upper().get()), annotations);
    } else {
      return Type.WildUnboundedTy.create(annotations);
    }
  }
}
