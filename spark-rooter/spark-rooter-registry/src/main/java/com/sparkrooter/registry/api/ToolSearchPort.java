package com.sparkrooter.registry.api;

import com.sparkrooter.contracts.model.ToolSearch;
import java.util.Set;

/**
 * 控制面对外公开的发现接口（project-structure §2：跨模块只依赖对方 api 包）。 不带身份：按 status / 风险策略过滤，宿主定义了 ToolAccessPolicy
 * 时再按它过滤；不含任何注册 / 转发能力。
 */
public interface ToolSearchPort {

  /**
   * 按领域返回候选工具（六字段投影）。
   *
   * @param sessionId 宿主会话键，透传给 ToolAccessPolicy（HTTP 直调 / 自检可为 null）
   */
  ToolSearch.Response search(ToolSearch.Request request, String sessionId);

  /** 可发现工具所属领域集合（去重）。供意图分类的枚举校验用。 */
  Set<String> domains();
}
