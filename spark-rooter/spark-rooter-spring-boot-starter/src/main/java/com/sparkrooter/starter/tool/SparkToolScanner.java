package com.sparkrooter.starter.tool;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.gateway.application.InvokeToolUseCase;
import com.sparkrooter.registry.application.RegisterToolUseCase;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.spi.ToolContext;
import com.sparkrooter.spi.ToolNameSink;
import com.sparkrooter.spi.annotation.SparkTool;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * 启动期扫描全部单例 Bean 的 @SparkTool 方法：推导 Manifest → 注册进 Registry → 生成代理调用适配器注册进 Gateway → 回填 displayName
 * → 写 ToolMetaRegistry。SmartInitializingSingleton 早于 ApplicationReadyEvent（StartupManifestRegistrar
 * / SelfCheckRunner 在其后）。
 *
 * <p>启动失败（指明原因）的情况：方法非 public、所在类 final、方法 final、标在 @Configuration 类上、签名不是 {@code Out m(In)} 或
 * {@code Out m(In, ToolContext)}、In / Out 非 record、两方法同 id、与手写 Manifest 同 id@version。
 */
public final class SparkToolScanner implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(SparkToolScanner.class);

  private final ApplicationContext context;
  private final ManifestDeriver deriver;
  private final RegisterToolUseCase register;
  private final InvokeToolUseCase gateway;
  private final ToolMetaRegistry meta;
  private final List<ToolNameSink> nameSinks;

  public SparkToolScanner(
      ApplicationContext context,
      ManifestDeriver deriver,
      RegisterToolUseCase register,
      InvokeToolUseCase gateway,
      ToolMetaRegistry meta,
      List<ToolNameSink> nameSinks) {
    this.context = context;
    this.deriver = deriver;
    this.register = register;
    this.gateway = gateway;
    this.meta = meta;
    this.nameSinks = List.copyOf(nameSinks);
  }

  @Override
  public void afterSingletonsInstantiated() {
    int tools = 0;
    Set<String> beans = new HashSet<>();
    for (String name : context.getBeanDefinitionNames()) {
      Object bean;
      try {
        bean = context.getBean(name); // 必须是代理对象
      } catch (RuntimeException e) {
        continue; // 抽象 / 非单例 / 不可实例化的定义
      }
      Class<?> target = AopUtils.getTargetClass(bean);
      for (Method m : target.getMethods()) {
        SparkTool tool = m.getAnnotation(SparkTool.class);
        if (tool == null) {
          continue;
        }
        registerOne(bean, target, m, tool);
        tools++;
        beans.add(name);
      }
    }
    log.info("spark-rooter: {} tools registered from {} beans", tools, beans.size());
  }

  private void registerOne(Object proxy, Class<?> target, Method m, SparkTool tool) {
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
          "@SparkTool class must not be final (cannot be proxied, host aspects would silently not apply): "
              + target.getName());
    }
    if (target.isAnnotationPresent(Configuration.class)) {
      throw new IllegalStateException(
          "@SparkTool must not be declared on a @Configuration class: " + where);
    }
    Class<?>[] params = m.getParameterTypes();
    boolean wantsCtx = params.length == 2 && params[1] == ToolContext.class;
    if (!(params.length == 1 || wantsCtx)) {
      throw new IllegalStateException(
          "@SparkTool signature must be Out m(In) or Out m(In, ToolContext): " + where);
    }
    ManifestDeriver.Derived d = deriver.derive(m, params[0], m.getReturnType());
    // 先写元数据表（同 id 二次注册在这里失败），再注册 Manifest（与手写来源同 id@version 冲突在这里失败）
    meta.register(d.meta());
    ToolManifest registered = register.execute(d.manifest());
    // 可在代理上调用的 Method：CGLIB 代理用最具体的重写，JDK 接口代理用接口方法
    Method specific = ClassUtils.getMostSpecificMethod(m, proxy.getClass());
    Method invocable = AopUtils.selectInvocableMethod(specific, proxy.getClass());
    gateway.registerHandler(
        new AnnotatedToolHandler(
            tool.id(), tool.version(), proxy, invocable, params[0], wantsCtx, deriver.mapper()));
    nameSinks.forEach(s -> s.register(registered.toolId(), registered.name()));
    log.info("spark-rooter: registered {} from {}", registered.key(), where);
  }
}
