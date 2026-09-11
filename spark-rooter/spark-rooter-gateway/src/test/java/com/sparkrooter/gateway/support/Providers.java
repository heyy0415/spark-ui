package com.sparkrooter.gateway.support;

import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

/** 测试用 ObjectProvider：InvokeToolUseCase 只调 getIfAvailable(Supplier)；bean 为 null 表示宿主未定义。 */
public final class Providers {

  private Providers() {}

  public static <T> ObjectProvider<T> none() {
    return of(null);
  }

  public static <T> ObjectProvider<T> of(T bean) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        if (bean == null) {
          throw new IllegalStateException("no bean");
        }
        return bean;
      }

      @Override
      public T getObject(Object... args) {
        return getObject();
      }

      @Override
      public T getIfAvailable() {
        return bean;
      }

      @Override
      public T getIfAvailable(Supplier<T> defaultSupplier) {
        return bean != null ? bean : defaultSupplier.get();
      }

      @Override
      public T getIfUnique() {
        return bean;
      }

      @Override
      public Stream<T> stream() {
        return bean == null ? Stream.empty() : Stream.of(bean);
      }

      @Override
      public Stream<T> orderedStream() {
        return stream();
      }
    };
  }
}
