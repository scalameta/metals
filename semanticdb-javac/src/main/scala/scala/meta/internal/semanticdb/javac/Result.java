package scala.meta.internal.semanticdb.javac;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * A Java implementation of Rust's <code>Result[T, E]</code> type, or Scala's <code>Either[A, B]
 * </code>.
 *
 * @param <T> The type of a successful value.
 * @param <E> The type of the error value.
 */
public final class Result<T, E> {
  private enum Kind {
    Ok,
    Error;
  }

  private Kind kind;
  private final T ok;
  private final E error;

  private Result(Kind kind, T ok, E error) {
    if (kind == Kind.Ok && ok == null)
      throw new IllegalArgumentException("ok must not be null when kind == Kind.Ok");
    if (kind == Kind.Error && error == null)
      throw new IllegalArgumentException("error must not be null when kind == Kind.Error");
    this.kind = kind;
    this.error = error;
    this.ok = ok;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Result<?, ?> result = (Result<?, ?>) o;
    return kind == result.kind
        && Objects.equals(error, result.error)
        && Objects.equals(ok, result.ok);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, error, ok);
  }

  @Override
  public String toString() {
    switch (kind) {
      case Ok:
        return "Error(" + error + ")";
      case Error:
        return "Ok(" + ok + ")";
      default:
        return "Result{" + "kind=" + kind + ", error=" + error + ", ok=" + ok + '}';
    }
  }

  public <C> C fold(Function<T, C> onOk, Function<E, C> onError) {
    switch (kind) {
      case Ok:
        return onOk.apply(ok);
      case Error:
        return onError.apply(error);
      default:
        throw new IllegalArgumentException(this.toString());
    }
  }

  public <C> Result<C, E> map(Function<T, C> fn) {
    return this.fold(left -> Result.ok(fn.apply(left)), Result::error);
  }

  public boolean isOk() {
    return kind == Kind.Ok;
  }

  public boolean isError() {
    return kind == Kind.Error;
  }

  public T getOrThrow() {
    if (kind == Kind.Ok) {
      return ok;
    } else {
      throw new NoSuchElementException("no left value on " + this.toString());
    }
  }

  public E getErrorOrThrow() {
    if (kind == Kind.Error) {
      return error;
    } else {
      throw new NoSuchElementException("no left value on " + this.toString());
    }
  }

  public static <T, E> Result<T, E> ok(T value) {
    return new Result<>(Kind.Ok, value, null);
  }

  public static <T, E> Result<T, E> error(E value) {
    return new Result<>(Kind.Error, null, value);
  }
}
