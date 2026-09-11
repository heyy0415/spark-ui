package com.sparkrooter.starter;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 默认日志级别（安全相关，宿主可显式覆盖）。
 *
 * <p>Spring AI 的 {@code OpenAiChatModel} 在「响应为空」等分支会以 WARN 打印整个 {@code Prompt{...}}，其中同时包含
 * <b>用户原话</b> 与 <b>模型名</b>。二者都属于不得落日志的内容（agent-safety §5：日志不含用户原文；模型名 / 网关地址 / 密钥同属部署配置）。 spark
 * 自己的代码从不打印这些，但压不住第三方 logger，因此在这里把它降到 ERROR。
 *
 * <p>宿主若确实需要该日志排障，可在自己的配置里显式设置 {@code logging.level.org.springframework.ai.openai=WARN} 覆盖本默认值——
 * 此时请自行确保日志不外流。
 */
public class SparkLoggingDefaults implements EnvironmentPostProcessor {

  static final String LOGGER = "logging.level.org.springframework.ai.openai.OpenAiChatModel";
  private static final String SOURCE = "sparkRooterLoggingDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    // 最低优先级：放在属性源末尾，宿主的 application.yml / 命令行 / 环境变量都能覆盖
    if (env.getPropertySources().contains(SOURCE)) {
      return;
    }
    env.getPropertySources().addLast(new MapPropertySource(SOURCE, Map.of(LOGGER, "ERROR")));
  }
}
