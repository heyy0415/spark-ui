package com.sparkrooter.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.tool.ManifestDeriver;
import com.sparkrooter.spi.annotation.SparkTool;
import com.sparkrooter.spi.tool.AnnotatedToolHandler;
import com.sparkrooter.spi.tool.SparkToolSignature;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * provider 侧的 {@code @SparkTool} 扫描器。
 *
 * <p>与 hub 侧 {@code SparkToolScanner} 职责本质不同，故不共用：
 *
 * <ul>
 *   <li>hub 侧：注册进本地 Registry（供规划器发现）+ 注入 Gateway（供执行）
 *   <li>provider 侧：推送 Manifest 到<b>远端</b> hub + 注册本地执行入口
 * </ul>
 *
 * 强行共用会把 gateway / registry / runtime 拖进 provider，直接违背薄依赖目标。真正该共用的是 签名校验（{@link
 * SparkToolSignature}）与 Manifest 推导（{@link ManifestDeriver}），两者都已抽到共享层。
 *
 * <p>推导出的 Manifest 在此补两处 provider 专属字段：{@code protocol=http} 与 {@code provider} 坐标段——
 * 推导器不知道自己跑在哪种形态里，这是装配方的知识。
 */
public final class ProviderToolScanner implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(ProviderToolScanner.class);

  private final ApplicationContext context;
  private final ManifestDeriver deriver;
  private final ProviderToolRegistry registry;
  private final SparkProviderProperties props;

  public ProviderToolScanner(
      ApplicationContext context,
      ManifestDeriver deriver,
      ProviderToolRegistry registry,
      SparkProviderProperties props) {
    this.context = context;
    this.deriver = deriver;
    this.registry = registry;
    this.props = props;
  }

  @Override
  public void afterSingletonsInstantiated() {
    int tools = 0;
    Set<String> beans = new HashSet<>();
    for (String name : context.getBeanDefinitionNames()) {
      Object bean;
      try {
        bean = context.getBean(name); // 必须是代理对象，宿主切面才生效
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
    log.info(
        "spark-provider: {} tools scanned from {} beans, service={}",
        tools,
        beans.size(),
        props.serviceName());
  }

  private void registerOne(Object proxy, Class<?> target, Method m, SparkTool tool) {
    boolean wantsCtx =
        SparkToolSignature.validate(target, m, target.isAnnotationPresent(Configuration.class));
    Class<?>[] params = m.getParameterTypes();
    ManifestDeriver.Derived d = deriver.derive(m, params[0], m.getReturnType());

    // 推导器产出的是 in-process 形态；provider 侧改写为 http 并补坐标（契约要求 http ⇒ provider 必填）
    ObjectNode manifest = (ObjectNode) d.manifest();
    manifest.put("protocol", "http");
    ObjectNode provider = manifest.putObject("provider");
    provider.put("serviceName", props.serviceName());
    if (props.baseUrl() != null && !props.baseUrl().isBlank()) {
      provider.put("baseUrl", props.baseUrl());
    }

    // 可在代理上调用的 Method：CGLIB 代理用最具体的重写，JDK 接口代理用接口方法
    Method specific = ClassUtils.getMostSpecificMethod(m, proxy.getClass());
    Method invocable = AopUtils.selectInvocableMethod(specific, proxy.getClass());
    registry.register(
        manifest,
        new AnnotatedToolHandler(
            tool.id(), tool.version(), proxy, invocable, params[0], wantsCtx, deriver.mapper()));
    log.info(
        "spark-provider: scanned {}@{} from {}#{}",
        tool.id(),
        tool.version(),
        target.getSimpleName(),
        m.getName());
  }
}
