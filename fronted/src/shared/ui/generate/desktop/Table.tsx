import { Table as AntTable } from 'antd';
import type { RenderedComponentProps, TableProps } from '../types';

export function TableDesktop({ id, props }: RenderedComponentProps<TableProps>) {
  const columns = props.columns.map((c) => ({ key: c.key, dataIndex: c.key, title: c.title }));
  const dataSource = props.rows.map((r, i) => ({ key: String(i), ...r }));
  return (
    <div data-component-id={id}>
      <AntTable size="small" columns={columns} dataSource={dataSource} pagination={false} />
    </div>
  );
}
