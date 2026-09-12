package com.sparkrooter.spi.tool;

import com.sparkrooter.spi.ToolContext;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * {@code @SparkTool} 方法的签名校验，hub 与 provider 两侧共用。
 *
 * <p>抽出来是为了不让两侧漂移：这些规则的后果都是「宿主切面静默不生效」或「扫描器拿不到参数」， 一侧放宽就会出现同一个工具在单体形态合法、微服务形态非法（或反之）。
 *
 * <p>全部在启动期抛出，不留到运行时——工具声明错误应当让进程起不来，而不是等第一次调用才炸。
 */
public final class SparkToolSignature {

  private SparkToolSignature() {}

  /**
   * 校验一个候选方法，并回答它是否需要 {@link ToolContext} 参数。
   *
   * @param target 目标类（已解代理）
   * @param m 标注了 {@code @SparkTool} 的方法
   * @param configurationAnnotated 目标类是否标了 Spring 的配置类注解（由调用方判断，spi 不依赖 Spring）
   * @return true = 方法签名为 {@code Out m(In, ToolContext)}；false = {@code Out m(In)}
   * @throws IllegalStateException 任一规则不满足
   */
  public static boolean validate(Class<?> target, Method m, boolean configurationAnnotated) {
    String where = target.getSimpleName() + "#" + m.getName();
    if (!Modifier.isPublic(m.getModifiers())) {
      throw new IllegalStateException("@SparkTool method must be public: " + where);
    }
    if (Modifier.isFinal(m.getModifiers())) {
      throw new IllegalStateException(
          "@SparkTool method must not be final (host aspects would silently not apply): " + where);
    }
    if (Modifier.isFinal(target.getModifiers())) {
      throw new IllegalStateException(
          "@SparkTool class must not be final (cannot be proxied, host aspects would silently not"
              + " apply): "
              + target.getName());
    }
    if (configurationAnnotated) {
      throw new IllegalStateException(
          "@SparkTool must not be declared on a Spring configuration class: " + where);
    }
    Class<?>[] params = m.getParameterTypes();
    boolean wantsCtx = params.length == 2 && params[1] == ToolContext.class;
    if (!(params.length == 1 || wantsCtx)) {
      throw new IllegalStateException(
          "@SparkTool signature must be Out m(In) or Out m(In, ToolContext): " + where);
    }
    return wantsCtx;
  }
}
