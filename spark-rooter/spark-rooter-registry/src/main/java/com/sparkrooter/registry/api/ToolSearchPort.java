package com.sparkrooter.registry.api;

import com.sparkrooter.contracts.model.ToolSearch;
import java.util.Set;

/**
 * 控制面对外公开的发现接口（project-structure §2：跨模块只依赖对方 api 包）。 两个只读查询都**按 principal 过滤**（agent-safety
 * §2：Registry 查询必须带 principal），不含任何注册 / 转发能力。
 */
public interface ToolSearchPort {

  /** 按领域返回该 principal 可见的候选工具（六字段投影）。 */
  ToolSearch.Response search(ToolSearch.Request request);

  /** 该 principal 可见的领域集合（有权限且 status 可发现的工具所属 domain 去重）。供意图分类的枚举校验用。 */
  Set<String> domains(ToolSearch.Principal principal);
}
