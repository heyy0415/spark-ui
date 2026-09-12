#!/usr/bin/env node
/**
 * node .harness/scripts/mvn.mjs <args...>   （cwd 固定为 sparkRooterDir/，无需 -f）
 *
 * 统一 Maven 入口：优先 sparkRooterDir/mvnw，其次 PATH 上的 mvn。
 * 强制 JAVA_HOME 指向 JDK 21（backend-standard.md §1），本机 jenv 下存在 openjdk64-21。
 * sparkRooterDir/pom.xml 不存在时直接通过（骨架未初始化，不阻塞前端 CI）。
 */
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const sparkRooterDir = join(root, 'spark-rooter');

if (!existsSync(join(sparkRooterDir, 'pom.xml'))) {
  console.log('✓ mvn: sparkRooterDir/pom.xml not present, skipping backend build');
  process.exit(0);
}

const args = process.argv.slice(2);
const mvnw = join(sparkRooterDir, 'mvnw');
const cmd = existsSync(mvnw) ? mvnw : 'mvn';

// JDK 解析：显式 JAVA_HOME 优先（CI 由 actions/setup-java 装在与本机无关的路径），
// 其次本机常见安装位置。与 lib/java-home.sh 的顺序保持一致。
const env = { ...process.env };
{
  const candidates = [
    // 路径里不一定含 "21"（CI 的 JAVA_HOME 常形如 /opt/hostedtoolcache/Java_Temurin.../x64），
    // 所以只要它存在就优先采用，版本由 maven-enforcer 的 requireJavaVersion 兜底校验。
    env.JAVA_HOME,
    `${env.HOME}/.jenv/versions/21`,
    `${env.HOME}/.jenv/versions/openjdk64-21.0.11`,
    '/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home',
  ].filter(Boolean);
  const found = candidates.find((c) => existsSync(join(c, 'bin', 'java')));
  if (found) {
    env.JAVA_HOME = found;
    env.PATH = `${join(found, 'bin')}:${env.PATH}`;
  } else {
    console.error('✗ mvn: JDK 21 not found; set JAVA_HOME to a JDK 21 installation');
    process.exit(1);
  }
}

const r = spawnSync(cmd, args, { stdio: 'inherit', env, cwd: sparkRooterDir });
process.exit(r.status ?? 1);
