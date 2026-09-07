import { List } from 'antd-mobile';
import type { RenderedComponentProps, TableProps } from '../../registry/types';

/** 移动端无表格组件；每行渲染为一个 List 分组。 */
export function TableMobile({ id, props }: RenderedComponentProps<TableProps>) {
  return (
    <div data-component-id={id}>
      {props.rows.map((r, i) => (
        <List key={i} header={`第 ${i + 1} 行`}>
          {props.columns.map((c) => (
            <List.Item key={c.key} extra={String(r[c.key] ?? '')}>
              {c.title}
            </List.Item>
          ))}
        </List>
      ))}
    </div>
  );
}
