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

package com.google.turbine.parse;

import static com.google.turbine.parse.Token.COMMA;
import static com.google.turbine.parse.Token.IDENT;
import static com.google.turbine.parse.Token.INTERFACE;
import static com.google.turbine.parse.Token.LPAREN;
import static com.google.turbine.parse.Token.MINUS;
import static com.google.turbine.parse.Token.RPAREN;
import static com.google.turbine.parse.Token.SEMI;
import static com.google.turbine.tree.TurbineModifier.PROTECTED;
import static com.google.turbine.tree.TurbineModifier.PUBLIC;
import static com.google.turbine.tree.TurbineModifier.VARARGS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Anno;
import com.google.turbine.tree.Tree.ArrTy;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.ImportDecl;
import com.google.turbine.tree.Tree.Kind;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.ModDecl;
import com.google.turbine.tree.Tree.ModDirective;
import com.google.turbine.tree.Tree.ModExports;
import com.google.turbine.tree.Tree.ModOpens;
import com.google.turbine.tree.Tree.ModProvides;
import com.google.turbine.tree.Tree.ModRequires;
import com.google.turbine.tree.Tree.ModUses;
import com.google.turbine.tree.Tree.PkgDecl;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.tree.Tree.TyParam;
import com.google.turbine.tree.Tree.Type;
import com.google.turbine.tree.Tree.VarDecl;
import com.google.turbine.tree.Tree.WildTy;
import com.google.turbine.tree.TurbineModifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A parser for the subset of Java required for header compilation.
 *
 * <p>See JLS 19: https://docs.oracle.com/javase/specs/jls/se8/html/jls-19.html
 */
public class Parser {

  private static final String CTOR_NAME = "<init>";
  private final Lexer lexer;

  private Token token;
  private int position;

  public static CompUnit parse(String source) {
    return parse(new SourceFile(null, source));
  }

  public static CompUnit parse(SourceFile source) {
    return new Parser(new StreamLexer(new UnicodeEscapePreprocessor(source))).compilationUnit();
  }

  private Parser(Lexer lexer) {
    this.lexer = lexer;
    this.token = lexer.next();
  }

  public CompUnit compilationUnit() {
    // TODO(cushon): consider enforcing package, import, and declaration order
    // and make it bug-compatible with javac:
    // http://mail.openjdk.java.net/pipermail/compiler-dev/2013-August/006968.html
    Optional<PkgDecl> pkg = Optional.empty();
    Optional<ModDecl> mod = Optional.empty();
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    ImmutableList.Builder<ImportDecl> imports = ImmutableList.builder();
    ImmutableList.Builder<TyDecl> decls = ImmutableList.builder();
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    while (true) {
      switch (token) {
        case PACKAGE:
          {
            next();
            pkg = Optional.of(packageDeclaration(annos.build()));
            annos = ImmutableList.builder();
            break;
          }
        case IMPORT:
          {
            next();
            ImportDecl i = importDeclaration();
            if (i == null) {
              continue;
            }
            imports.add(i);
            break;
          }
        case PUBLIC:
          next();
          access.add(PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case AT:
          {
            int pos = position;
            next();
            if (token == INTERFACE) {
              decls.add(annotationDeclaration(access, annos.build()));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
            } else {
              annos.add(annotation(pos));
            }
            break;
          }
        case CLASS:
          decls.add(classDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case INTERFACE:
          decls.add(interfaceDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case ENUM:
          decls.add(enumDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case EOF:
          // TODO(cushon): check for dangling modifiers?
          return new CompUnit(position, pkg, mod, imports.build(), decls.build(), lexer.source());
        case SEMI:
          // TODO(cushon): check for dangling modifiers?
          next();
          continue;
        case IDENT:
          {
            Ident ident = ident();
            if (ident.value().equals("record")) {
              next();
              decls.add(recordDeclaration(access, annos.build()));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
              break;
            }
            if (ident.value().equals("sealed")) {
              next();
              access.add(TurbineModifier.SEALED);
              break;
            }
            if (ident.value().equals("non")) {
              int start = position;
              next();
              eatNonSealed(start);
              next();
              access.add(TurbineModifier.NON_SEALED);
              break;
            }
            if (access.isEmpty()
                && (ident.value().equals("module") || ident.value().equals("open"))) {
              boolean open = false;
              if (ident.value().equals("open")) {
                next();
                if (token != IDENT) {
                  throw error(token);
                }
                ident = ident();
                open = true;
              }
              if (!ident.value().equals("module")) {
                throw error(token);
              }
              next();
              mod = Optional.of(moduleDeclaration(open, annos.build()));
              annos = ImmutableList.builder();
              break;
            }
          }
        // fall through
        default:
          throw error(token);
      }
    }
  }

  // Handle the hypenated pseudo-keyword 'non-sealed'.
  //
  // This will need to be updated to handle other hyphenated keywords if when/they are introduced.
  private void eatNonSealed(int start) {
    eat(Token.MINUS);
    if (token != IDENT) {
      throw error(token);
    }
    if (!ident().value().equals("sealed")) {
      throw error(token);
    }
    if (position != start + "non-".length()) {
      throw error(token);
    }
  }

  private void next() {
    token = lexer.next();
    position = lexer.position();
  }

  private TyDecl recordDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    String javadoc = lexer.javadoc();
    int pos = position;
    Ident name = eatIdent();
    ImmutableList<TyParam> typarams;
    if (token == Token.LT) {
      typarams = typarams();
    } else {
      typarams = ImmutableList.of();
    }
    ImmutableList.Builder<VarDecl> formals = ImmutableList.builder();
    if (token == Token.LPAREN) {
      next();
      formalParams(formals, EnumSet.noneOf(TurbineModifier.class));
      eat(Token.RPAREN);
    }
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.IMPLEMENTS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        access,
        annos,
        name,
        typarams,
        Optional.<ClassTy>empty(),
        interfaces.build(),
        /* permits= */ ImmutableList.of(),
        members,
        formals.build(),
        TurbineTyKind.RECORD,
        javadoc);
  }

  private TyDecl interfaceDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    String javadoc = lexer.javadoc();
    eat(Token.INTERFACE);
    int pos = position;
    Ident name = eatIdent();
    ImmutableList<TyParam> typarams;
    if (token == Token.LT) {
      typarams = typarams();
    } else {
      typarams = ImmutableList.of();
    }
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.EXTENDS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    ImmutableList.Builder<ClassTy> permits = ImmutableList.builder();
    if (token == Token.IDENT) {
      if (ident().value().equals("permits")) {
        eat(Token.IDENT);
        do {
          permits.add(classty());
        } while (maybe(Token.COMMA));
      }
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        access,
        annos,
        name,
        typarams,
        Optional.<ClassTy>empty(),
        interfaces.build(),
        permits.build(),
        members,
        ImmutableList.of(),
        TurbineTyKind.INTERFACE,
        javadoc);
  }

  private TyDecl annotationDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    String javadoc = lexer.javadoc();
    eat(Token.INTERFACE);
    int pos = position;
    Ident name = eatIdent();
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        access,
        annos,
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>empty(),
        ImmutableList.<ClassTy>of(),
        ImmutableList.of(),
        members,
        ImmutableList.of(),
        TurbineTyKind.ANNOTATION,
        javadoc);
  }

  private TyDecl enumDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    String javadoc = lexer.javadoc();
    eat(Token.ENUM);
    int pos = position;
    Ident name = eatIdent();
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.IMPLEMENTS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members =
        ImmutableList.<Tree>builder().addAll(enumMembers(name)).addAll(classMembers()).build();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        access,
        annos,
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>empty(),
        interfaces.build(),
        ImmutableList.of(),
        members,
        ImmutableList.of(),
        TurbineTyKind.ENUM,
        javadoc);
  }

  private String moduleName() {
    return flatname('.', qualIdent());
  }

  private String packageName() {
    return flatname('/', qualIdent());
  }

  private ModDecl moduleDeclaration(boolean open, ImmutableList<Anno> annos) {
    int pos = position;
    String moduleName = moduleName();
    eat(Token.LBRACE);
    ImmutableList.Builder<ModDirective> directives = ImmutableList.builder();
    OUTER:
    while (true) {
      switch (token) {
        case IDENT:
          {
            String ident = lexer.stringValue();
            next();
            switch (ident) {
              case "requires":
                directives.add(moduleRequires());
                break;
              case "exports":
                directives.add(moduleExports());
                break;
              case "opens":
                directives.add(moduleOpens());
                break;
              case "uses":
                directives.add(moduleUses());
                break;
              case "provides":
                directives.add(moduleProvides());
                break;
              default: // fall out
            }
            break;
          }
        case RBRACE:
          break OUTER;
        default:
          throw error(token);
      }
    }
    eat(Token.RBRACE);
    return new ModDecl(pos, annos, open, moduleName, directives.build());
  }

  private static String flatname(char join, ImmutableList<Ident> idents) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Ident ident : idents) {
      if (!first) {
        sb.append(join);
      }
      sb.append(ident.value());
      first = false;
    }
    return sb.toString();
  }

  private ModRequires moduleRequires() {
    int pos = position;
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    while (true) {
      if (token == Token.IDENT && lexer.stringValue().equals("transitive")) {
        next();
        access.add(TurbineModifier.TRANSITIVE);
        continue;
      }
      if (token == Token.STATIC) {
        next();
        access.add(TurbineModifier.STATIC);
        continue;
      }
      break;
    }

    String moduleName = moduleName();
    eat(Token.SEMI);
    return new ModRequires(pos, ImmutableSet.copyOf(access), moduleName);
  }

  private ModExports moduleExports() {
    int pos = position;

    String packageName = packageName();
    ImmutableList.Builder<String> moduleNames = ImmutableList.builder();
    if (lexer.stringValue().equals("to")) {
      next();
      do {

        moduleNames.add(moduleName());
      } while (maybe(Token.COMMA));
    }
    eat(Token.SEMI);
    return new ModExports(pos, packageName, moduleNames.build());
  }

  private ModOpens moduleOpens() {
    int pos = position;

    String packageName = packageName();
    ImmutableList.Builder<String> moduleNames = ImmutableList.builder();
    if (lexer.stringValue().equals("to")) {
      next();
      do {

        String moduleName = moduleName();
        moduleNames.add(moduleName);
      } while (maybe(Token.COMMA));
    }
    eat(Token.SEMI);
    return new ModOpens(pos, packageName, moduleNames.build());
  }

  private ModUses moduleUses() {
    int pos = position;
    ImmutableList<Ident> uses = qualIdent();
    eat(Token.SEMI);
    return new ModUses(pos, uses);
  }

  private ModProvides moduleProvides() {
    int pos = position;
    ImmutableList<Ident> typeName = qualIdent();
    if (!eatIdent().value().equals("with")) {
      throw error(token);
    }
    ImmutableList.Builder<ImmutableList<Ident>> implNames = ImmutableList.builder();
    do {
      ImmutableList<Ident> implName = qualIdent();
      implNames.add(implName);
    } while (maybe(Token.COMMA));
    eat(Token.SEMI);
    return new ModProvides(pos, typeName, implNames.build());
  }

  private static final ImmutableSet<TurbineModifier> ENUM_CONSTANT_MODIFIERS =
      ImmutableSet.of(
          TurbineModifier.PUBLIC,
          TurbineModifier.STATIC,
          TurbineModifier.ACC_ENUM,
          TurbineModifier.FINAL);

  private ImmutableList<Tree> enumMembers(Ident enumName) {
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    OUTER:
    while (true) {
      switch (token) {
        case IDENT:
          {
            String javadoc = lexer.javadoc();
            Ident name = eatIdent();
            if (token == Token.LPAREN) {
              dropParens();
            }
            EnumSet<TurbineModifier> access = EnumSet.copyOf(ENUM_CONSTANT_MODIFIERS);
            // TODO(cushon): consider desugaring enum constants later
            if (token == Token.LBRACE) {
              dropBlocks();
              access.add(TurbineModifier.ENUM_IMPL);
            }
            maybe(Token.COMMA);
            result.add(
                new VarDecl(
                    position,
                    access,
                    annos.build(),
                    new ClassTy(
                        position,
                        Optional.<ClassTy>empty(),
                        enumName,
                        ImmutableList.<Type>of(),
                        ImmutableList.of()),
                    name,
                    Optional.<Expression>empty(),
                    javadoc));
            annos = ImmutableList.builder();
            break;
          }
        case SEMI:
          next();
          annos = ImmutableList.builder();
          break OUTER;
        case RBRACE:
          annos = ImmutableList.builder();
          break OUTER;
        case AT:
          int pos = position;
          next();
          annos.add(annotation(pos));
          break;
        default:
          throw error(token);
      }
    }
    return result.build();
  }

  private TyDecl classDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    String javadoc = lexer.javadoc();
    eat(Token.CLASS);
    int pos = position;
    Ident name = eatIdent();
    ImmutableList<TyParam> tyParams = ImmutableList.of();
    if (token == Token.LT) {
      tyParams = typarams();
    }
    ClassTy xtnds = null;
    if (token == Token.EXTENDS) {
      next();
      xtnds = classty();
    }
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.IMPLEMENTS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    ImmutableList.Builder<ClassTy> permits = ImmutableList.builder();
    if (token == Token.IDENT) {
      if (ident().value().equals("permits")) {
        eat(Token.IDENT);
        do {
          permits.add(classty());
        } while (maybe(Token.COMMA));
      }
    }
    switch (token) {
      case LBRACE:
        next();
        break;
      case EXTENDS:
        throw error(ErrorKind.EXTENDS_AFTER_IMPLEMENTS);
      default:
        throw error(ErrorKind.EXPECTED_TOKEN, Token.LBRACE);
    }
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        access,
        annos,
        name,
        tyParams,
        Optional.ofNullable(xtnds),
        interfaces.build(),
        permits.build(),
        members,
        ImmutableList.of(),
        TurbineTyKind.CLASS,
        javadoc);
  }

  private ImmutableList<Tree> classMembers() {
    ImmutableList.Builder<Tree> acc = ImmutableList.builder();
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    while (true) {
      switch (token) {
        case PUBLIC:
          next();
          access.add(TurbineModifier.PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(TurbineModifier.PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case NATIVE:
          next();
          access.add(TurbineModifier.NATIVE);
          break;
        case SYNCHRONIZED:
          next();
          access.add(TurbineModifier.SYNCHRONIZED);
          break;
        case TRANSIENT:
          next();
          access.add(TurbineModifier.TRANSIENT);
          break;
        case VOLATILE:
          next();
          access.add(TurbineModifier.VOLATILE);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case DEFAULT:
          next();
          access.add(TurbineModifier.DEFAULT);
          break;
        case AT:
          {
            // TODO(cushon): de-dup with top-level parsing
            int pos = position;
            next();
            if (token == INTERFACE) {
              acc.add(annotationDeclaration(access, annos.build()));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
            } else {
              annos.add(annotation(pos));
            }
            break;
          }

        case IDENT:
          Ident ident = ident();
          if (ident.value().equals("sealed")) {
            next();
            access.add(TurbineModifier.SEALED);
            break;
          }
          if (ident.value().equals("non")) {
            int pos = position;
            next();
            if (token != MINUS) {
              acc.addAll(member(access, annos.build(), ImmutableList.of(), pos, ident));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
            } else {
              eatNonSealed(pos);
              next();
              access.add(TurbineModifier.NON_SEALED);
            }
            break;
          }
          if (ident.value().equals("record")) {
            eat(IDENT);
            acc.add(recordDeclaration(access, annos.build()));
            access = EnumSet.noneOf(TurbineModifier.class);
            annos = ImmutableList.builder();
            break;
          }
        // fall through
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case CHAR:
        case DOUBLE:
        case FLOAT:
        case VOID:
        case LT:
          acc.addAll(classMember(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case LBRACE:
          dropBlocks();
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case CLASS:
          acc.add(classDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case INTERFACE:
          acc.add(interfaceDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case ENUM:
          acc.add(enumDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case RBRACE:
          return acc.build();
        case SEMI:
          next();
          continue;
        default:
          throw error(token);
      }
    }
  }

  private ImmutableList<Tree> classMember(
      EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    ImmutableList<TyParam> typaram = ImmutableList.of();
    Type result;
    Ident name;

    if (token == Token.LT) {
      typaram = typarams();
    }

    if (token == Token.AT) {
      annos = ImmutableList.<Anno>builder().addAll(annos).addAll(maybeAnnos()).build();
    }

    switch (token) {
      case VOID:
        {
          result = new Tree.VoidTy(position);
          next();
          int pos = position;
          name = eatIdent();
          return memberRest(pos, access, annos, typaram, result, name);
        }
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case CHAR:
      case DOUBLE:
      case FLOAT:
        {
          result = referenceType(ImmutableList.of());
          int pos = position;
          name = eatIdent();
          return memberRest(pos, access, annos, typaram, result, name);
        }
      case IDENT:
        int pos = position;
        Ident ident = eatIdent();
        return member(access, annos, typaram, pos, ident);
      default:
        throw error(token);
    }
  }

  private ImmutableList<Tree> member(
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      ImmutableList<TyParam> typaram,
      int pos,
      Ident ident) {
    Type result;
    Ident name;
    switch (token) {
      case LPAREN:
        {
          name = ident;
          return ImmutableList.of(methodRest(pos, access, annos, typaram, null, name));
        }
      case LBRACE:
        {
          dropBlocks();
          name = new Ident(position, CTOR_NAME);
          String javadoc = lexer.javadoc();
          access.add(TurbineModifier.COMPACT_CTOR);
          return ImmutableList.<Tree>of(
              new MethDecl(
                  pos,
                  access,
                  annos,
                  typaram,
                  /* ret= */ Optional.empty(),
                  name,
                  /* params= */ ImmutableList.of(),
                  /* exntys= */ ImmutableList.of(),
                  /* defaultValue= */ Optional.empty(),
                  javadoc));
        }
      case IDENT:
        {
          result =
              new ClassTy(
                  position,
                  Optional.<ClassTy>empty(),
                  ident,
                  ImmutableList.<Type>of(),
                  ImmutableList.of());
          pos = position;
          name = eatIdent();
          return memberRest(pos, access, annos, typaram, result, name);
        }
      case AT:
      case LBRACK:
        {
          result =
              new ClassTy(
                  position,
                  Optional.<ClassTy>empty(),
                  ident,
                  ImmutableList.<Type>of(),
                  ImmutableList.of());
          break;
        }
      case LT:
        {
          result =
              new ClassTy(position, Optional.<ClassTy>empty(), ident, tyargs(), ImmutableList.of());
          break;
        }
      case DOT:
        result =
            new ClassTy(
                position,
                Optional.<ClassTy>empty(),
                ident,
                ImmutableList.<Type>of(),
                ImmutableList.of());
        break;

      default:
        throw error(token);
    }
    if (result == null) {
      throw error(token);
    }
    if (token == Token.DOT) {
      next();
      if (!result.kind().equals(Kind.CLASS_TY)) {
        throw error(token);
      }
      result = classty((ClassTy) result);
    }
    result = maybeDims(result);
    pos = position;
    name = eatIdent();
    switch (token) {
      case LPAREN:
        return ImmutableList.of(methodRest(pos, access, annos, typaram, result, name));
      case LBRACK:
      case SEMI:
      case ASSIGN:
      case COMMA:
        {
          if (!typaram.isEmpty()) {
            throw error(ErrorKind.UNEXPECTED_TYPE_PARAMETER, typaram);
          }
          return fieldRest(pos, access, annos, result, name);
        }
      default:
        throw error(token);
    }
  }

  private ImmutableList<Anno> maybeAnnos() {
    if (token != Token.AT) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<Anno> builder = ImmutableList.builder();
    while (token == Token.AT) {
      int pos = position;
      next();
      builder.add(annotation(pos));
    }
    return builder.build();
  }

  private ImmutableList<Tree> memberRest(
      int pos,
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      ImmutableList<TyParam> typaram,
      Type result,
      Ident name) {
    switch (token) {
      case ASSIGN:
      case AT:
      case COMMA:
      case LBRACK:
      case SEMI:
        {
          if (!typaram.isEmpty()) {
            throw error(ErrorKind.UNEXPECTED_TYPE_PARAMETER, typaram);
          }
          return fieldRest(pos, access, annos, result, name);
        }
      case LPAREN:
        return ImmutableList.of(methodRest(pos, access, annos, typaram, result, name));
      default:
        throw error(token);
    }
  }

  private ImmutableList<Tree> fieldRest(
      int pos,
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      Type baseTy,
      Ident name) {
    String javadoc = lexer.javadoc();
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    VariableInitializerParser initializerParser = new VariableInitializerParser(token, lexer);
    List<List<SavedToken>> bits = initializerParser.parseInitializers();
    token = initializerParser.token;

    boolean first = true;
    int expressionStart = pos;
    for (List<SavedToken> bit : bits) {
      IteratorLexer lexer = new IteratorLexer(this.lexer.source(), bit.iterator());
      Parser parser = new Parser(lexer);
      if (first) {
        first = false;
      } else {
        name = parser.eatIdent();
      }
      Type ty = baseTy;
      ty = parser.extraDims(ty);
      // TODO(cushon): skip more fields that are definitely non-const
      ConstExpressionParser constExpressionParser =
          new ConstExpressionParser(lexer, lexer.next(), lexer.position());
      expressionStart = lexer.position();
      Expression init = constExpressionParser.expression();
      if (init != null && init.kind() == Tree.Kind.ARRAY_INIT) {
        init = null;
      }
      result.add(new VarDecl(pos, access, annos, ty, name, Optional.ofNullable(init), javadoc));
    }
    if (token != SEMI) {
      throw TurbineError.format(lexer.source(), expressionStart, ErrorKind.UNTERMINATED_EXPRESSION);
    }
    eat(Token.SEMI);
    return result.build();
  }

  private Tree methodRest(
      int pos,
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      ImmutableList<TyParam> typaram,
      Type result,
      Ident name) {
    String javadoc = lexer.javadoc();
    eat(Token.LPAREN);
    ImmutableList.Builder<VarDecl> formals = ImmutableList.builder();
    formalParams(formals, access);
    eat(Token.RPAREN);

    result = extraDims(result);

    ImmutableList.Builder<ClassTy> exceptions = ImmutableList.builder();
    if (token == Token.THROWS) {
      next();
      exceptions.addAll(exceptions());
    }
    Tree defaultValue = null;
    switch (token) {
      case SEMI:
        next();
        break;
      case LBRACE:
        dropBlocks();
        break;
      case DEFAULT:
        {
          ConstExpressionParser cparser =
              new ConstExpressionParser(lexer, lexer.next(), lexer.position());
          Tree expr = cparser.expression();
          token = cparser.token;
          if (expr == null && token == Token.AT) {
            int annoPos = position;
            next();
            expr = annotation(annoPos);
          }
          if (expr == null) {
            throw error(token);
          }
          defaultValue = expr;
          eat(Token.SEMI);
          break;
        }
      default:
        throw error(token);
    }
    if (result == null) {
      name = new Ident(position, CTOR_NAME);
    }
    return new MethDecl(
        pos,
        access,
        annos,
        typaram,
        Optional.<Tree>ofNullable(result),
        name,
        formals.build(),
        exceptions.build(),
        Optional.ofNullable(defaultValue),
        javadoc);
  }

  /**
   * Given a base {@code type} and some number of {@code extra} c-style array dimension specifiers,
   * construct a new array type.
   *
   * <p>For reasons that are unclear from the spec, {@code int @A [] x []} is equivalent to {@code
   * int [] @A [] x}, not {@code int @A [] [] x}.
   */
  private Type extraDims(Type ty) {
    return maybeDims(ty);
  }

  private ImmutableList<ClassTy> exceptions() {
    ImmutableList.Builder<ClassTy> result = ImmutableList.builder();
    result.add(classty());
    while (maybe(Token.COMMA)) {
      result.add(classty());
    }
    return result.build();
  }

  private void formalParams(
      ImmutableList.Builder<VarDecl> builder, EnumSet<TurbineModifier> access) {
    while (token != Token.RPAREN) {
      VarDecl formal = formalParam();
      builder.add(formal);
      if (formal.mods().contains(TurbineModifier.VARARGS)) {
        access.add(TurbineModifier.VARARGS);
      }
      if (token != Token.COMMA) {
        break;
      }
      next();
    }
  }

  private VarDecl formalParam() {
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    EnumSet<TurbineModifier> access = modifiersAndAnnotations(annos);
    Type ty = referenceTypeWithoutDims(ImmutableList.of());
    ty = paramDims(access, ty);
    // the parameter name is `this` for receiver parameters, and a qualified this expression
    // for inner classes
    Ident name = identOrThis();
    while (token == Token.DOT) {
      eat(Token.DOT);
      // Overwrite everything up to the terminal 'this' for inner classes; we don't need it
      name = identOrThis();
    }
    ty = extraDims(ty);
    return new VarDecl(
        position, access, annos.build(), ty, name, Optional.<Expression>empty(), null);
  }

  private Type paramDims(EnumSet<TurbineModifier> access, Type ty) {
    ImmutableList<Anno> typeAnnos = maybeAnnos();
    switch (token) {
      case LBRACK:
        next();
        eat(Token.RBRACK);
        return new ArrTy(position, typeAnnos, paramDims(access, ty));
      case ELLIPSIS:
        next();
        access.add(VARARGS);
        return new ArrTy(position, typeAnnos, ty);
      default:
        if (!typeAnnos.isEmpty()) {
          throw error(token);
        }
        return ty;
    }
  }

  private Ident identOrThis() {
    switch (token) {
      case IDENT:
        return eatIdent();
      case THIS:
        int position = lexer.position();
        eat(Token.THIS);
        return new Ident(position, "this");
      default:
        throw error(token);
    }
  }

  private void dropParens() {
    eat(Token.LPAREN);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RPAREN:
          depth--;
          break;
        case LPAREN:
          depth++;
          break;
        case EOF:
          throw error(ErrorKind.UNEXPECTED_EOF);
        default:
          break;
      }
      next();
    }
  }

  private void dropBlocks() {
    eat(Token.LBRACE);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RBRACE:
          depth--;
          break;
        case LBRACE:
          depth++;
          break;
        case EOF:
          throw error(ErrorKind.UNEXPECTED_EOF);
        default:
          break;
      }
      next();
    }
  }

  private ImmutableList<TyParam> typarams() {
    ImmutableList.Builder<TyParam> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    while (true) {
      ImmutableList<Anno> annotations = maybeAnnos();
      int pos = position;
      Ident name = eatIdent();
      ImmutableList<Tree> bounds = ImmutableList.of();
      if (token == Token.EXTENDS) {
        next();
        bounds = tybounds();
      }
      acc.add(new TyParam(pos, name, bounds, annotations));
      switch (token) {
        case COMMA:
          eat(Token.COMMA);
          continue;
        case GT:
          next();
          break OUTER;
        default:
          throw error(token);
      }
    }
    return acc.build();
  }

  private ImmutableList<Tree> tybounds() {
    ImmutableList.Builder<Tree> acc = ImmutableList.builder();
    do {
      acc.add(classty());
    } while (maybe(Token.AND));
    return acc.build();
  }

  private ClassTy classty() {
    return classty(null);
  }

  private ClassTy classty(ClassTy ty) {
    return classty(ty, null);
  }

  private ClassTy classty(ClassTy ty, @Nullable ImmutableList<Anno> typeAnnos) {
    int pos = position;
    do {
      if (typeAnnos == null) {
        typeAnnos = maybeAnnos();
      }
      Ident name = eatIdent();
      ImmutableList<Type> tyargs = ImmutableList.of();
      if (token == Token.LT) {
        tyargs = tyargs();
      }
      ty = new ClassTy(pos, Optional.ofNullable(ty), name, tyargs, typeAnnos);
      typeAnnos = null;
    } while (maybe(Token.DOT));
    return ty;
  }

  private ImmutableList<Type> tyargs() {
    ImmutableList.Builder<Type> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    do {
      ImmutableList<Anno> typeAnnos = maybeAnnos();
      switch (token) {
        case COND:
          {
            next();
            switch (token) {
              case EXTENDS:
                next();
                Type upper = referenceType(maybeAnnos());
                acc.add(
                    new WildTy(position, typeAnnos, Optional.of(upper), Optional.<Type>empty()));
                break;
              case SUPER:
                next();
                Type lower = referenceType(maybeAnnos());
                acc.add(
                    new WildTy(position, typeAnnos, Optional.<Type>empty(), Optional.of(lower)));
                break;
              case COMMA:
                acc.add(
                    new WildTy(
                        position, typeAnnos, Optional.<Type>empty(), Optional.<Type>empty()));
                continue OUTER;
              case GT:
              case GTGT:
              case GTGTGT:
                acc.add(
                    new WildTy(
                        position, typeAnnos, Optional.<Type>empty(), Optional.<Type>empty()));
                break OUTER;
              default:
                throw error(token);
            }
            break;
          }
        case IDENT:
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case CHAR:
        case DOUBLE:
        case FLOAT:
          acc.add(referenceType(typeAnnos));
          break;
        default:
          throw error(token);
      }
    } while (maybe(Token.COMMA));
    switch (token) {
      case GT:
        next();
        break;
      case GTGT:
        token = Token.GT;
        break;
      case GTGTGT:
        token = Token.GTGT;
        break;
      default:
        throw error(token);
    }
    return acc.build();
  }

  private Type referenceTypeWithoutDims(ImmutableList<Anno> typeAnnos) {
    switch (token) {
      case IDENT:
        return classty(null, typeAnnos);
      case BOOLEAN:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.BOOLEAN);
      case BYTE:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.BYTE);
      case SHORT:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.SHORT);
      case INT:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.INT);
      case LONG:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.LONG);
      case CHAR:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.CHAR);
      case DOUBLE:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.DOUBLE);
      case FLOAT:
        next();
        return new PrimTy(position, typeAnnos, TurbineConstantTypeKind.FLOAT);
      default:
        throw error(token);
    }
  }

  private Type referenceType(ImmutableList<Anno> typeAnnos) {
    Type ty = referenceTypeWithoutDims(typeAnnos);
    return maybeDims(ty);
  }

  private Type maybeDims(Type ty) {
    ImmutableList<Anno> typeAnnos = maybeAnnos();
    if (maybe(Token.LBRACK)) {
      eat(Token.RBRACK);
      return new ArrTy(position, typeAnnos, maybeDims(ty));
    }
    if (!typeAnnos.isEmpty()) {
      throw error(token);
    }
    return ty;
  }

  private EnumSet<TurbineModifier> modifiersAndAnnotations(ImmutableList.Builder<Anno> annos) {
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    while (true) {
      switch (token) {
        case PUBLIC:
          next();
          access.add(TurbineModifier.PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(TurbineModifier.PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case NATIVE:
          next();
          access.add(TurbineModifier.NATIVE);
          break;
        case SYNCHRONIZED:
          next();
          access.add(TurbineModifier.SYNCHRONIZED);
          break;
        case TRANSIENT:
          next();
          access.add(TurbineModifier.TRANSIENT);
          break;
        case VOLATILE:
          next();
          access.add(TurbineModifier.VOLATILE);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case AT:
          int pos = position;
          next();
          annos.add(annotation(pos));
          break;
        default:
          return access;
      }
    }
  }

  private ImportDecl importDeclaration() {
    boolean stat = maybe(Token.STATIC);

    int pos = position;
    ImmutableList.Builder<Ident> type = ImmutableList.builder();
    type.add(eatIdent());
    boolean wild = false;
    OUTER:
    while (maybe(Token.DOT)) {
      switch (token) {
        case IDENT:
          type.add(eatIdent());
          break;
        case MULT:
          eat(Token.MULT);
          wild = true;
          break OUTER;
        default:
          break;
      }
    }
    eat(Token.SEMI);
    return new ImportDecl(pos, type.build(), stat, wild);
  }

  private PkgDecl packageDeclaration(ImmutableList<Anno> annos) {
    PkgDecl result = new PkgDecl(position, qualIdent(), annos);
    eat(Token.SEMI);
    return result;
  }

  private ImmutableList<Ident> qualIdent() {
    ImmutableList.Builder<Ident> name = ImmutableList.builder();
    name.add(eatIdent());
    while (maybe(Token.DOT)) {
      name.add(eatIdent());
    }
    return name.build();
  }

  private Anno annotation(int pos) {
    ImmutableList<Ident> name = qualIdent();

    ImmutableList.Builder<Expression> args = ImmutableList.builder();
    if (token == Token.LPAREN) {
      eat(LPAREN);
      while (token != RPAREN) {
        ConstExpressionParser cparser = new ConstExpressionParser(lexer, token, position);
        Expression arg = cparser.expression();
        if (arg == null) {
          throw error(ErrorKind.INVALID_ANNOTATION_ARGUMENT);
        }
        args.add(arg);
        token = cparser.token;
        if (!maybe(COMMA)) {
          break;
        }
      }
      eat(Token.RPAREN);
    }

    return new Anno(pos, name, args.build());
  }

  private Ident ident() {
    int position = lexer.position();
    String value = lexer.stringValue();
    return new Ident(position, value);
  }

  private Ident eatIdent() {
    Ident ident = ident();
    eat(Token.IDENT);
    return ident;
  }

  private void eat(Token kind) {
    if (token != kind) {
      throw error(ErrorKind.EXPECTED_TOKEN, kind);
    }
    next();
  }

  @CanIgnoreReturnValue
  private boolean maybe(Token kind) {
    if (token == kind) {
      next();
      return true;
    }
    return false;
  }

  TurbineError error(Token token) {
    switch (token) {
      case IDENT:
        return error(ErrorKind.UNEXPECTED_IDENTIFIER, lexer.stringValue());
      case EOF:
        return error(ErrorKind.UNEXPECTED_EOF);
      default:
        return error(ErrorKind.UNEXPECTED_TOKEN, token);
    }
  }

  private TurbineError error(ErrorKind kind, Object... args) {
    return TurbineError.format(
        lexer.source(),
        Math.min(lexer.position(), lexer.source().source().length() - 1),
        kind,
        args);
  }
}
