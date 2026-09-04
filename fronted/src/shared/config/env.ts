import { z } from 'zod';

/**
 * 环境变量在进入应用前统一 Zod 校验（coding-standard.md §2）。
 * 任何新增的 VITE_* 变量都在这里声明，不允许在业务代码里直接读 import.meta.env。
 */
const EnvSchema = z.object({
  // 后端端点已带完整前缀（/agent/runs、/actuator），默认同源直连；dev 由 vite proxy 转发到 8080
  VITE_API_BASE_URL: z.string().default(''),
  MODE: z.string(),
  DEV: z.boolean(),
  PROD: z.boolean(),
});

export type Env = z.infer<typeof EnvSchema>;

export const env: Env = EnvSchema.parse(import.meta.env);
