import { Button, Space, Table as AntTable } from 'antd';
import type { RenderedComponentProps, TableProps, TableRow } from '../../registry/types';

/**
 * 桌面 Table：列来自 columns；rows[].actions 渲染为末列按钮，点击只回调 intent 原文（handlers.onIntent）。
 * total > rows.length 时表尾显示 emptyText；不分页。
 */
export function TableDesktop({ id, props, handlers }: RenderedComponentProps<TableProps>) {
  const hasActions = props.rows.some((r) => r.actions && r.actions.length > 0);
  const columns = [
    ...props.columns.map((c) => ({
      key: c.key,
      title: c.title,
      render: (_: unknown, row: TableRow) => row.cells[c.key] ?? '',
    })),
    ...(hasActions
      ? [
          {
            key: '__actions',
            title: '操作',
            render: (_: unknown, row: TableRow) => (
              <Space wrap>
                {(row.actions ?? []).map((a, i) => (
                  <Button
                    key={i}
                    size="small"
                    data-intent={a.intent}
                    disabled={handlers?.onIntent === undefined}
                    onClick={() => handlers?.onIntent?.(a.intent)}
                  >
                    {a.label}
                  </Button>
                ))}
              </Space>
            ),
          },
        ]
      : []),
  ];
  const showFooter =
    props.emptyText !== undefined &&
    (props.rows.length === 0 || (props.total !== undefined && props.total > props.rows.length));
  return (
    <div data-component-id={id}>
      <AntTable<TableRow>
        size="small"
        rowKey="id"
        columns={columns}
        dataSource={props.rows}
        pagination={false}
        locale={{ emptyText: props.emptyText ?? '暂无数据' }}
        {...(showFooter && props.rows.length > 0 ? { footer: () => props.emptyText } : {})}
      />
    </div>
  );
}
