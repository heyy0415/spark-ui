package com.sparkrooter.webmvc.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.registry.application.RegisterToolUseCase;
import com.sparkrooter.registry.application.SearchToolsUseCase;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制面端点。只做注册 / 发现 / 版本查询；没有任何转发或调用端点（agent-safety §1）。 Controller 只做参数绑定与响应映射，规则在 application /
 * domain。
 */
@RestController
@RequestMapping("/internal/tool-registry")
public class ToolRegistryController {

  private static final Logger log = LoggerFactory.getLogger(ToolRegistryController.class);

  private final RegisterToolUseCase register;
  private final SearchToolsUseCase search;
  private final ToolRegistryRepository repo;
  private final SchemaValidator validator;

  public ToolRegistryController(
      RegisterToolUseCase register,
      SearchToolsUseCase search,
      ToolRegistryRepository repo,
      SchemaValidator validator) {
    this.register = register;
    this.search = search;
    this.repo = repo;
    this.validator = validator;
  }

  @PostMapping("/tools")
  public ResponseEntity<ToolManifest> register(@RequestBody JsonNode manifest) {
    log.info("register_tool, toolId={}", manifest.path("toolId").asText("?"));
    return ResponseEntity.status(HttpStatus.CREATED).body(register.execute(manifest));
  }

  @PostMapping("/search")
  public ToolSearch.Response search(@RequestBody JsonNode body) {
    ToolSearch.Request req =
        validator.bind("tool-search", "#/$defs/request", body, ToolSearch.Request.class);
    log.info("search_tools, domain={}", req.domain());
    return search.execute(req, null);
  }

  @GetMapping("/tools/{toolId}/versions")
  public List<ToolManifest> versions(@PathVariable String toolId) {
    log.info("list_versions, toolId={}", toolId);
    return repo.findVersions(toolId);
  }
}
