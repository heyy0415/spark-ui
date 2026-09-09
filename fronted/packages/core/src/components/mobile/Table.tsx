import { Button, List, Space } from 'antd-mobile';
import type { RenderedComponentProps, TableProps } from '../../registry/types';

/** 移动端无表格组件：每行渲染为一个 List 分组，尾部放行内指令按钮；表尾 emptyText。 */
export function TableMobile({ id, props, handlers }: RenderedComponentProps<TableProps>) {
  const showFooter =
    props.emptyText !== undefined && props.total !== undefined && props.total > props.rows.length;
  return (
    <div data-component-id={id}>
      {props.rows.length === 0 ? <List.Item>{props.emptyText ?? '暂无数据'}</List.Item> : null}
      {props.rows.map((r) => (
        <List key={r.id} header={r.cells[props.columns[0]?.key ?? ''] ?? r.id}>
          {props.columns.slice(1).map((c) => (
            <List.Item key={c.key} extra={r.cells[c.key] ?? ''}>
              {c.title}
            </List.Item>
          ))}
          {r.actions && r.actions.length > 0 ? (
            <List.Item>
              <Space wrap>
                {r.actions.map((a, i) => (
                  <Button
                    key={i}
                    size="mini"
                    data-intent={a.intent}
                    disabled={handlers?.onIntent === undefined}
                    onClick={() => handlers?.onIntent?.(a.intent)}
                  >
                    {a.label}
                  </Button>
                ))}
              </Space>
            </List.Item>
          ) : null}
        </List>
      ))}
      {showFooter ? (
        <List>
          <List.Item>{props.emptyText}</List.Item>
        </List>
      ) : null}
    </div>
  );
}
