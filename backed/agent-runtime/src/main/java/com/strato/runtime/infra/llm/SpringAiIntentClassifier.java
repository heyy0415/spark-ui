package com.strato.runtime.infra.llm;

import com.strato.runtime.application.DomainDescriptions;
import com.strato.runtime.application.port.IntentClassifier;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Spring AI 实现：模型只输出 {"domain": "..."} 结构化结果。Prompt 只给领域名与一句话说明，不给工具列表； 输出不在 knownDomains 内（含
 * "none"、解析失败、异常）一律 empty。日志不落用户原文。
 */
public final class SpringAiIntentClassifier implements IntentClassifier {

  private static final Logger log = LoggerFactory.getLogger(SpringAiIntentClassifier.class);
  static final String NONE = "none";

  /** 模型结构化输出。 */
  public record IntentDraft(String domain) {}

  private final ChatClient chat;
  private final String model;

  public SpringAiIntentClassifier(ChatClient chat, String model) {
    this.chat = chat;
    this.model = model;
  }

  @Override
  public Optional<String> classify(
      String message, Optional<String> entityType, Set<String> knownDomains) {
    if (knownDomains.isEmpty()) {
      return Optional.empty();
    }
    try {
      IntentDraft draft =
          chat.prompt()
              .options(
                  OpenAiChatOptions.builder()
                      .model(model)
                      .internalToolExecutionEnabled(false)
                      .temperature(0.0)
                      .build())
              .system(systemPrompt(knownDomains))
              .user(userPrompt(message, entityType))
              .call()
              .entity(IntentDraft.class);
      String d = draft == null || draft.domain() == null ? NONE : draft.domain().trim();
      if (knownDomains.contains(d)) {
        return Optional.of(d);
      }
      if (!NONE.equals(d)) {
        // 模型发明了领域：按 none 处理。不打印返回串本身（模型可能回显用户原文），只记录长度
        log.warn("classifier returned unknown domain (len={}), treated as none", d.length());
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      log.warn("classifier call failed, treated as none: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  static String systemPrompt(Set<String> knownDomains) {
    String domains =
        knownDomains.stream()
            .sorted()
            .map(d -> "- " + d + "：" + DomainDescriptions.of(d))
            .collect(Collectors.joining("\n"));
    return """
        你是企业内部请求的领域分类器。只能从下列领域中选择一个，或者输出 none。
        输出必须是 JSON 对象：{"domain":"<领域名或none>"}，不要输出任何其他文字。
        与任何领域无关的请求（闲聊、天气、问候、无法判断）输出 none。
        候选领域：
        %s
        """
        .formatted(domains);
  }

  static String userPrompt(String message, Optional<String> entityType) {
    String hint = entityType.map(t -> "用户正在查看一个 " + PromptBuilder.sanitize(t) + "。").orElse("");
    return """
        %s
        用户请求：%s
        """
        .formatted(hint, PromptBuilder.sanitize(message));
  }

  @Override
  public String name() {
    return "spring-ai";
  }
}
