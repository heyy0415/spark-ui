package com.sparkrooter.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.spi.ProviderAuth;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 启动后把本进程扫描到的 Manifest 推送给 hub。
 *
 * <p>选「provider 推送」而非「hub 拉取」：provider 只需配 hub 地址，新增工具自动上报，无需改 hub 配置。 代价是 provider 重启漏推时需要人工介入（hub
 * 侧的对账补偿留给后续 change）。
 *
 * <p>在 {@link ApplicationReadyEvent} 而非扫描器内推送：此时本进程的 web 端点已就绪，hub 收到注册后 立即回调也能被正确处理。若在扫描期推送，hub
 * 可能调到一个还没起完的端点。
 *
 * <p><b>推送失败不阻断启动</b>：领域服务的本职是提供业务能力，hub 暂时不可达不该让它起不来。 失败记 ERROR 日志并保留堆栈，由运维据日志重启或手工触发。
 */
public final class ManifestPublisher {

  private static final Logger log = LoggerFactory.getLogger(ManifestPublisher.class);

  /** hub 的注册端点（与 hub 侧 ToolRegistryController 的 @RequestMapping 一致）。 */
  static final String REGISTER_PATH = "/internal/tool-registry/tools";

  private final ProviderToolRegistry registry;
  private final SparkProviderProperties props;
  private final ProviderAuth auth;
  private final RestClient client;

  public ManifestPublisher(
      ProviderToolRegistry registry, SparkProviderProperties props, ProviderAuth auth) {
    this.registry = registry;
    this.props = props;
    this.auth = auth;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(SparkProviderProperties.HUB_CONNECT_TIMEOUT_MS));
    factory.setReadTimeout(Duration.ofMillis(SparkProviderProperties.HUB_READ_TIMEOUT_MS));
    this.client = RestClient.builder().baseUrl(props.hubUrl()).requestFactory(factory).build();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void publishOnStartup() {
    if (!props.shouldPublishOnStartup()) {
      log.info("spark-provider: publish-on-startup disabled, {} manifests held", registry.size());
      return;
    }
    publishAll();
  }

  /**
   * 推送全部 Manifest。逐个推送而非批量：hub 的注册端点是单个 Manifest 语义，且逐个推送能让 某一个工具声明有问题时其余照常注册（部分可用优于全部不可用）。
   *
   * @return 成功推送的数量
   */
  public int publishAll() {
    int ok = 0;
    for (JsonNode manifest : registry.manifests()) {
      String key = manifest.path("toolId").asText("?") + "@" + manifest.path("version").asText("?");
      try {
        client
            .post()
            .uri(REGISTER_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .header(ProviderAuth.HEADER, auth.outboundToken(props.serviceName()))
            .body(manifest)
            .retrieve()
            .toBodilessEntity();
        ok++;
        log.info("spark-provider: published {} to hub={}", key, props.hubUrl());
      } catch (RestClientException e) {
        // 不阻断启动：领域服务的本职是业务能力，hub 暂时不可达不该让它起不来
        log.error("spark-provider: failed to publish {} to hub={}", key, props.hubUrl(), e);
      }
    }
    log.info("spark-provider: published {}/{} manifests", ok, registry.size());
    return ok;
  }
}
