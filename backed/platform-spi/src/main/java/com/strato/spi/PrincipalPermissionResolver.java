package com.strato.spi;

import java.util.Set;

/** 首期内存权限表；后续由 IdP 实现替换。 */
public interface PrincipalPermissionResolver {
  Set<String> permissionsOf(Principal principal);
}
