/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.diag.SourceFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.FileObject;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

/** Turbine's implementation of {@link Filer}. */
public class TurbineFiler implements Filer {

  /**
   * Existing paths of file objects that cannot be regenerated, including the original compilation
   * inputs and source or class files generated during any annotation processing round.
   */
  private final Set<String> seen;

  /**
   * File objects generated during the current processing round. Each entry has a unique path, which
   * is enforced by {@link #seen}.
   */
  private final List<TurbineJavaFileObject> files = new ArrayList<>();

  /** Loads resources from the classpath. */
  private final Function<String, Supplier<byte[]>> classPath;

  /** The {@link ClassLoader} for the annotation processor path, for loading resources. */
  private final ClassLoader loader;

  private final Map<String, SourceFile> generatedSources = new LinkedHashMap<>();
  private final Map<String, byte[]> generatedClasses = new LinkedHashMap<>();

  /** Generated source file objects from all rounds. */
  public ImmutableMap<String, SourceFile> generatedSources() {
    return ImmutableMap.copyOf(generatedSources);
  }

  /** Generated class file objects from all rounds. */
  public ImmutableMap<String, byte[]> generatedClasses() {
    return ImmutableMap.copyOf(generatedClasses);
  }

  public TurbineFiler(
      Set<String> seen, Function<String, Supplier<byte[]>> classPath, ClassLoader loader) {
    this.seen = seen;
    this.classPath = classPath;
    this.loader = loader;
  }

  /**
   * Called when the current annotation processing round is complete, and returns the sources
   * generated in that round.
   */
  public Collection<SourceFile> finishRound() {
    Map<String, SourceFile> roundSources = new LinkedHashMap<>();
    for (TurbineJavaFileObject e : files) {
      String path = e.getName();
      switch (e.getKind()) {
        case SOURCE:
          roundSources.put(path, new SourceFile(path, e.contents()));
          break;
        case CLASS:
          generatedClasses.put(path, e.bytes());
          break;
        case OTHER:
          switch (e.location()) {
            case CLASS_OUTPUT:
              generatedClasses.put(path, e.bytes());
              break;
            case SOURCE_OUTPUT:
              this.generatedSources.put(path, new SourceFile(path, e.contents()));
              break;
            default:
              throw new AssertionError(e.location());
          }
          break;
        case HTML:
          throw new UnsupportedOperationException(String.valueOf(e.getKind()));
      }
    }
    files.clear();
    this.generatedSources.putAll(roundSources);
    return roundSources.values();
  }

  @Override
  public JavaFileObject createSourceFile(CharSequence n, Element... originatingElements)
      throws IOException {
    String name = n.toString();
    checkArgument(!name.contains("/"), "invalid type name: %s", name);
    return create(StandardLocation.SOURCE_OUTPUT, Kind.SOURCE, name.replace('.', '/') + ".java");
  }

  @Override
  public JavaFileObject createClassFile(CharSequence n, Element... originatingElements)
      throws IOException {
    String name = n.toString();
    checkArgument(!name.contains("/"), "invalid type name: %s", name);
    return create(StandardLocation.CLASS_OUTPUT, Kind.CLASS, name.replace('.', '/') + ".class");
  }

  @Override
  public FileObject createResource(
      Location location, CharSequence p, CharSequence r, Element... originatingElements)
      throws IOException {
    checkArgument(location instanceof StandardLocation, "%s", location);
    String pkg = p.toString();
    String relativeName = r.toString();
    checkArgument(!pkg.contains("/"), "invalid package: %s", pkg);
    String path = packageRelativePath(pkg, relativeName);
    return create((StandardLocation) location, Kind.OTHER, path);
  }

  private JavaFileObject create(StandardLocation location, Kind kind, String path)
      throws FilerException {
    checkArgument(location.isOutputLocation());
    if (!seen.add(path)) {
      throw new FilerException("already created " + path);
    }
    TurbineJavaFileObject result = new TurbineJavaFileObject(location, kind, path);
    files.add(result);
    return result;
  }

  @Override
  public FileObject getResource(Location location, CharSequence p, CharSequence r)
      throws IOException {
    String pkg = p.toString();
    String relativeName = r.toString();
    checkArgument(!pkg.contains("/"), "invalid package: %s", pkg);
    checkArgument(location instanceof StandardLocation, "unsupported location %s", location);
    StandardLocation standardLocation = (StandardLocation) location;
    String path = packageRelativePath(pkg, relativeName);
    switch (standardLocation) {
      case CLASS_OUTPUT:
        byte[] generated = generatedClasses.get(path);
        if (generated == null) {
          throw new FileNotFoundException(path);
        }
        return new BytesFileObject(path, Suppliers.ofInstance(generated));
      case SOURCE_OUTPUT:
        SourceFile source = generatedSources.get(path);
        if (source == null) {
          throw new FileNotFoundException(path);
        }
        return new SourceFileObject(path, source.source());
      case ANNOTATION_PROCESSOR_PATH:
        if (loader.getResource(path) == null) {
          throw new FileNotFoundException(path);
        }
        return new ResourceFileObject(loader, path);
      case CLASS_PATH:
        Supplier<byte[]> bytes = classPath.apply(path);
        if (bytes == null) {
          throw new FileNotFoundException(path);
        }
        return new BytesFileObject(path, bytes);
      default:
        throw new IllegalArgumentException(standardLocation.getName());
    }
  }

  private static String packageRelativePath(String pkg, String relativeName) {
    if (pkg.isEmpty()) {
      return relativeName;
    }
    return pkg.replace('.', '/') + '/' + relativeName;
  }

  private abstract static class ReadOnlyFileObject implements FileObject {

    protected final String path;

    public ReadOnlyFileObject(String path) {
      this.path = path;
    }

    @Override
    public final String getName() {
      return path;
    }

    @Override
    public URI toUri() {
      return URI.create("file:///" + path);
    }

    @Override
    public final OutputStream openOutputStream() {
      throw new IllegalStateException();
    }

    @Override
    public final Writer openWriter() {
      throw new IllegalStateException();
    }

    @Override
    public final long getLastModified() {
      return 0;
    }

    @Override
    public final boolean delete() {
      throw new IllegalStateException();
    }
  }

  private abstract static class WriteOnlyFileObject implements FileObject {

    @Override
    public final InputStream openInputStream() {
      throw new IllegalStateException();
    }

    @Override
    public final Reader openReader(boolean ignoreEncodingErrors) {
      throw new IllegalStateException();
    }

    @Override
    public final CharSequence getCharContent(boolean ignoreEncodingErrors) {
      throw new IllegalStateException();
    }
  }

  private static class TurbineJavaFileObject extends WriteOnlyFileObject implements JavaFileObject {

    private final StandardLocation location;
    private final Kind kind;
    private final CharSequence name;
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

    public TurbineJavaFileObject(StandardLocation location, Kind kind, CharSequence name) {
      this.location = location;
      this.kind = kind;
      this.name = name;
    }

    @Override
    public Kind getKind() {
      return kind;
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
      throw new UnsupportedOperationException();
    }

    @Override
    public NestingKind getNestingKind() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Modifier getAccessLevel() {
      throw new UnsupportedOperationException();
    }

    @Override
    public URI toUri() {
      return URI.create("file:///" + name + kind.extension);
    }

    @Override
    public String getName() {
      return name.toString();
    }

    @Override
    public OutputStream openOutputStream() {
      return baos;
    }

    @Override
    public Writer openWriter() {
      return new OutputStreamWriter(openOutputStream(), UTF_8);
    }

    @Override
    public long getLastModified() {
      return 0;
    }

    @Override
    public boolean delete() {
      throw new IllegalStateException();
    }

    public byte[] bytes() {
      return baos.toByteArray();
    }

    public String contents() {
      return new String(baos.toByteArray(), UTF_8);
    }

    public StandardLocation location() {
      return location;
    }
  }

  private static class ResourceFileObject extends ReadOnlyFileObject {

    private final ClassLoader loader;

    public ResourceFileObject(ClassLoader loader, String path) {
      super(path);
      this.loader = loader;
    }

    @Override
    public URI toUri() {
      try {
        return requireNonNull(loader.getResource(path)).toURI();
      } catch (URISyntaxException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public InputStream openInputStream() {
      return loader.getResourceAsStream(path);
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
      return new InputStreamReader(openInputStream(), UTF_8);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
      return new String(openInputStream().readAllBytes(), UTF_8);
    }
  }

  private static class BytesFileObject extends ReadOnlyFileObject {

    private final Supplier<byte[]> bytes;

    public BytesFileObject(String path, Supplier<byte[]> bytes) {
      super(path);
      this.bytes = bytes;
    }

    @Override
    public InputStream openInputStream() {
      return new ByteArrayInputStream(bytes.get());
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) {
      return new InputStreamReader(openInputStream(), UTF_8);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return new String(bytes.get(), UTF_8);
    }
  }

  private static class SourceFileObject extends ReadOnlyFileObject {

    private final String source;

    public SourceFileObject(String path, String source) {
      super(path);
      this.source = source;
    }

    @Override
    public InputStream openInputStream() {
      return new ByteArrayInputStream(source.getBytes(UTF_8));
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) {
      return new StringReader(source);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
