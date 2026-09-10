import { Button, Card as AntCard, Descriptions, Space, Typography } from 'antd';
import type { CardProps, LabelValue, RenderedComponentProps } from '../../registry/types';

/** tone → antd Typography.Text type；default 不上色。 */
function toneType(tone: LabelValue['tone']): 'success' | 'warning' | 'danger' | undefined {
  return tone === undefined || tone === 'default' ? undefined : tone;
}

/**
 * 桌面 Card：title / description / items；底部 actions（行内指令）渲染为按钮，点击只回调 intent 原文（handlers.onIntent），
 * 与 Table 行内按钮同一路径——多级界面（详情 → 返回列表 / 查看物流）全部由后端预写自然语言驱动。
 */
export function CardDesktop({ id, props, handlers }: RenderedComponentProps<CardProps>) {
  const actions = props.actions ?? [];
  // exactOptionalPropertyTypes：antd 的 actions 不接受显式 undefined，无 actions 时不传该属性
  const actionBar =
    actions.length > 0
      ? [
          <Space key="actions" wrap>
            {actions.map((a, i) => (
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
          </Space>,
        ]
      : null;
  return (
    <AntCard
      title={props.title}
      data-component-id={id}
      size="small"
      {...(actionBar ? { actions: actionBar } : {})}
    >
      {props.description ? <p style={{ margin: 0 }}>{props.description}</p> : null}
      {props.items && props.items.length > 0 ? (
        <Descriptions
          size="small"
          column={1}
          items={props.items.map((it, i) => {
            const t = toneType(it.tone);
            return {
              key: String(i),
              label: it.label,
              children: t ? <Typography.Text type={t}>{it.value}</Typography.Text> : it.value,
            };
          })}
        />
      ) : null}
    </AntCard>
  );
}
