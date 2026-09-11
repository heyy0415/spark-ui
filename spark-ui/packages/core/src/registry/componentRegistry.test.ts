import { describe, expect, it } from 'vitest';
import { COMPONENT_TYPES } from '../schema/uiSchema';
import { REGISTRY_KEYS, desktopRegistry, mobileRegistry } from './componentRegistry';

/** 白名单注册表：两端键集合一致且等于契约 enum；冻结，宿主运行期不可写入（agent-safety §4）。 */
describe('componentRegistry', () => {
  it('desktop and mobile registries expose the same keys as COMPONENT_TYPES', () => {
    const expected = [...COMPONENT_TYPES];
    for (const keys of [
      Object.keys(desktopRegistry),
      Object.keys(mobileRegistry),
      [...REGISTRY_KEYS],
    ]) {
      expect(keys).toHaveLength(expected.length);
      expect(keys).toEqual(expect.arrayContaining(expected));
    }
  });

  it('registries are frozen', () => {
    expect(Object.isFrozen(desktopRegistry)).toBe(true);
    expect(Object.isFrozen(mobileRegistry)).toBe(true);
  });

  it('every entry is a lazy component (object with $$typeof), not a string path', () => {
    for (const key of REGISTRY_KEYS) {
      expect(typeof desktopRegistry[key]).toBe('object');
      expect(typeof mobileRegistry[key]).toBe('object');
    }
  });
});
