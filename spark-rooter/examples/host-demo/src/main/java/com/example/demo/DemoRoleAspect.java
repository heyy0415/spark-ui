package com.example.demo;

import com.sparkrooter.examples.support.DemoUserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>正面路径</b>：方法级权限。spark 经代理调用 @SparkTool 方法 → 本切面触发 → 角色不足抛异常 → Gateway HANDLER_ERROR → run.failed
 * TOOL_EXECUTION_FAILED。同时切 order.delete 方法（示例领域不 import 宿主，注解无法标在领域类上，用切点表达式代替）。
 */
@Aspect
@Component
public class DemoRoleAspect {

  private static final Logger log = LoggerFactory.getLogger(DemoRoleAspect.class);

  @Around("@annotation(role)")
  public Object requireAnnotatedRole(ProceedingJoinPoint pjp, DemoRequiresRole role) throws Throwable {
    return require(pjp, role.value());
  }

  /** order.delete 需要 admin。 */
  @Around("execution(* com.sparkrooter.examples.order.infra.OrderTools.delete(..))")
  public Object requireAdminForDelete(ProceedingJoinPoint pjp) throws Throwable {
    return require(pjp, "admin");
  }

  private Object require(ProceedingJoinPoint pjp, String role) throws Throwable {
    DemoUserContext.DemoUser user = DemoUserContext.currentOrDefault();
    if (!user.hasRole(role)) {
      log.warn("denied user={} method={} requiredRole={}", user.userId(), pjp.getSignature().toShortString(), role);
      throw new IllegalStateException("permission denied: requires role " + role);
    }
    return pjp.proceed();
  }
}
