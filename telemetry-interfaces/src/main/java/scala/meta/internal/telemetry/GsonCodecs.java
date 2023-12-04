package scala.meta.internal.telemetry;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import java.lang.Iterable;

public final class GsonCodecs {

	public static class OptionalTypeAdapter<E> extends TypeAdapter<Optional<E>> {

		public static final TypeAdapterFactory FACTORY = new TypeAdapterFactory() {
			@Override
			public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
				Class<T> rawType = (Class<T>) type.getRawType();
				if (rawType != Optional.class) {
					return null;
				}
				final ParameterizedType parameterizedType = (ParameterizedType) type.getType();
				final Type actualType = parameterizedType.getActualTypeArguments()[0];
				final TypeAdapter<?> adapter = gson.getAdapter(TypeToken.get(actualType));
				return new OptionalTypeAdapter(adapter);
			}
		};
		private final TypeAdapter<E> adapter;

		public OptionalTypeAdapter(TypeAdapter<E> adapter) {
			this.adapter = adapter;
		}

		@Override
		public Optional<E> read(JsonReader in) throws IOException {
			if (in.peek() != JsonToken.NULL) {
				return Optional.ofNullable(adapter.read(in));
			} else {
				in.nextNull();
				return Optional.empty();
			}
		}

		@Override
		public void write(JsonWriter out, Optional<E> value) throws IOException {
			if (value.isPresent()) {
				adapter.write(out, value.get());
			} else {
				out.nullValue();
			}
		}
	}

	final static class PostProcessingTypeAdapterFactory implements TypeAdapterFactory {
		static PostProcessingTypeAdapterFactory of(final Consumer<Object>... postProcessorsArgs) {
			return new PostProcessingTypeAdapterFactory(Arrays.asList(postProcessorsArgs.clone()));
		}

		static PostProcessingTypeAdapterFactory of(final Iterable<? extends Consumer<Object>> postProcessorsArgs) {
			return new PostProcessingTypeAdapterFactory(postProcessorsArgs);
		}

		private final Iterable<? extends Consumer<Object>> postProcessors;

		private PostProcessingTypeAdapterFactory(final Iterable<? extends Consumer<Object>> postProcessors) {
			this.postProcessors = postProcessors;
		}

		@Override
		public <T> TypeAdapter<T> create(final Gson gson, final TypeToken<T> typeToken) {
			final TypeAdapter<T> delegateAdapter = gson.getDelegateAdapter(this, typeToken);
			return new TypeAdapter<T>() {
				@Override
				public void write(final JsonWriter out, final T value) throws IOException {
					delegateAdapter.write(out, value);
				}

				@Override
				public T read(final JsonReader in) throws IOException {
					final T value = delegateAdapter.read(in);
					for (final Consumer<Object> postProcessor : postProcessors) {
						postProcessor.accept(value);
					}
					return value;
				}
			};
		}
	}

	/* Sets missing Optional<V> fields in JSON to Optional.empty */
	final static Consumer<Object> missingOptionalFieldsPostProcessor = new Consumer<Object>() {
		@Override
		public void accept(final Object o) {
			try {
				final String objectPackage = o.getClass().getPackage().getName();
				final String telemetryModelPackage = this.getClass().getPackage().getName();
				// Modify only known classess defined in the same package
				if (objectPackage.startsWith(telemetryModelPackage)) {
					final Field[] declaredFields = o.getClass().getDeclaredFields();
					for (final Field field : declaredFields) {
						if (field.getType() == Optional.class && !Modifier.isStatic(field.getModifiers())) {
							field.setAccessible(true);
							if (field.get(o) == null) {
								field.set(o, Optional.empty());
							}
						}
					}
				}
			} catch (final IllegalAccessException ex) {
				throw new RuntimeException("Failed to fill optional fields in " + o.getClass().getName(), ex);
			}
		}
	};

	public static Gson gson = new GsonBuilder().disableHtmlEscaping()
			.registerTypeAdapterFactory(OptionalTypeAdapter.FACTORY)
			.registerTypeAdapterFactory(PostProcessingTypeAdapterFactory.of(missingOptionalFieldsPostProcessor))
			.create();

}