/**
 * @spark-ui/core 唯一公共入口。清单固定，由 scripts/verify-pack.mjs 断言（spec §2.2）。
 * 接入方只能从这里 import；不要 import 包内深路径。
 */
export { SchemaRenderer } from './renderer/SchemaRenderer';
export type { SchemaRendererProps } from './renderer/SchemaRenderer';
export { ActionBar } from './renderer/ActionBar';
export { UnknownComponent } from './renderer/UnknownComponent';

export { desktopRegistry, mobileRegistry, REGISTRY_KEYS } from './registry/componentRegistry';
export { PROPS_SCHEMAS } from './registry/types';
export type { FormValues, ActionBarProps, ComponentHandlers } from './registry/types';

export {
  UiSchemaSchema,
  UiComponentSchema,
  UiActionSchema,
  FormPropsSchema,
  COMPONENT_TYPES,
  parseUiSchema,
} from './schema/uiSchema';
export type {
  UiSchema,
  UiComponent,
  UiAction,
  ComponentType,
  FormProps,
  CardProps,
  TableProps,
  TableRow,
  ResultProps,
  TimelineProps,
  LabelValue,
  InlineAction,
} from './schema/uiSchema';

export { SparkThemeProvider } from './theme/SparkThemeProvider';
export type { SparkThemeTokens, SparkThemeProviderProps } from './theme/SparkThemeProvider';
export { SparkDeviceProvider } from './device/SparkDeviceProvider';
export { useDevice, MOBILE_MAX_WIDTH } from './device/DeviceContext';
export type { DeviceKind } from './device/DeviceContext';
