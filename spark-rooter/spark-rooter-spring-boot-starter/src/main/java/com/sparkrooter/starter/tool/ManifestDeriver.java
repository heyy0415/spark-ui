package com.sparkrooter.starter.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.spi.annotation.EntityType;
import com.sparkrooter.spi.annotation.ParamFormat;
import com.sparkrooter.spi.annotation.SparkDefault;
import com.sparkrooter.spi.annotation.SparkParam;
import com.sparkrooter.spi.annotation.SparkPrerequisite;
import com.sparkrooter.spi.annotation.SparkRisk;
import com.sparkrooter.spi.annotation.SparkTool;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 从 @SparkTool 方法推导 Manifest（spec §2.4 类型映射表）。输入：只有 @SparkParam 组件进 inputSchema，required =
 * 无 @SparkDefault 且非 Optional / @Nullable；输出：Out 全部组件。嵌套 record →
 * object（additionalProperties:false），List → array，Java enum → enum 常量名，BigDecimal → 金额字符串，LocalDate
 * / Instant / OffsetDateTime → date / date-time。Map / 通配泛型 / 非 record 类 → 启动失败。
 */
public final class ManifestDeriver {

  static final String MONEY_PATTERN = "^\\d+(\\.\\d{1,2})?$";

  private final ObjectMapper mapper;
  private final SchemaValidator validator;
  private final String ownerTeam;

  public ObjectMapper mapper() {
    return mapper;
  }

  public ManifestDeriver(SchemaValidator validator, String ownerTeam) {
    this.validator = validator;
    this.mapper = validator.mapper();
    this.ownerTeam = ownerTeam;
  }

  /** 推导结果：契约校验过的 Manifest JSON + 供规划器的元数据。 */
  public record Derived(JsonNode manifest, ToolMetaRegistry.ToolMeta meta) {}

  public Derived derive(Method method, Class<?> in, Class<?> out) {
    SparkTool tool = method.getAnnotation(SparkTool.class);
    SparkRisk risk = method.getAnnotation(SparkRisk.class);
    SparkPrerequisite pre = method.getAnnotation(SparkPrerequisite.class);
    String where = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    if (tool.description().isBlank()) {
      throw new IllegalStateException("@SparkTool on " + where + " requires non-blank description");
    }
    if (!tool.id().startsWith(tool.domain() + ".")) {
      throw new IllegalStateException(
          "@SparkTool id must start with domain: " + tool.id() + " / " + tool.domain());
    }
    requireRecord(in, "In of " + where);
    requireRecord(out, "Out of " + where);

    ObjectNode m = mapper.createObjectNode();
    m.put("toolId", tool.id());
    m.put("version", tool.version());
    m.put("domain", tool.domain());
    m.put("name", tool.name());
    m.put("description", tool.description());
    m.put("protocol", "in-process");
    Map<String, ToolMetaRegistry.ParamMeta> params = new LinkedHashMap<>();
    m.set("inputSchema", inputSchema(in, params, where));
    m.set("outputSchema", recordSchema(out, true, where));
    m.set("risk", riskNode(risk));
    m.putObject("authorization"); // 内核无权限语义：{}
    ObjectNode exec = m.putObject("execution");
    exec.put("timeoutMs", risk == null ? 3000 : risk.timeoutMs());
    exec.put("maxRetries", risk == null ? 1 : risk.maxRetries());
    exec.put("idempotency", risk == null ? "none" : lower(risk.idempotency()));
    m.putObject("owner").put("team", ownerTeam);
    m.put("status", "active");
    validator.assertValid("tool-manifest", m);

    ToolMetaRegistry.ToolMeta meta =
        new ToolMetaRegistry.ToolMeta(
            tool.id(),
            tool.version(),
            tool.domain(),
            pre == null ? List.of() : Arrays.asList(pre.value()),
            tool.clarifiesEntity(),
            params);
    return new Derived(m, meta);
  }

  private static ObjectNode riskNode(SparkRisk risk) {
    ObjectNode r = new ObjectMapper().createObjectNode();
    r.put("level", risk == null ? "low" : lower(risk.level()));
    r.put("sideEffect", risk != null && risk.sideEffect());
    r.put("reversible", risk == null || risk.reversible());
    r.put("confirmation", risk == null ? "never" : lower(risk.confirmation()));
    return r;
  }

  /** inputSchema：只收 @SparkParam 组件；无 @SparkDefault 且非可空 → required。 */
  private ObjectNode inputSchema(
      Class<?> in, Map<String, ToolMetaRegistry.ParamMeta> params, String where) {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    List<String> required = new ArrayList<>();
    ObjectNode props = mapper.createObjectNode();
    for (RecordComponent rc : in.getRecordComponents()) {
      SparkParam p = rc.getAnnotation(SparkParam.class);
      if (p == null) {
        continue; // 未标注不开放
      }
      SparkDefault def = rc.getAnnotation(SparkDefault.class);
      boolean nullable = isNullable(rc);
      ObjectNode prop = typeSchema(unwrap(rc.getGenericType()), p, where + "." + rc.getName());
      if (def != null) {
        prop.set("default", literal(def.value(), unwrap(rc.getGenericType())));
      } else if (!nullable) {
        required.add(rc.getName());
      }
      props.set(rc.getName(), prop);
      params.put(rc.getName(), paramMeta(rc.getName(), p, def, unwrap(rc.getGenericType())));
    }
    if (!required.isEmpty()) {
      ArrayNode req = schema.putArray("required");
      required.forEach(req::add);
    }
    schema.set("properties", props);
    return schema;
  }

  /** record → object schema；输出侧全部组件进 properties，非可空进 required。 */
  private ObjectNode recordSchema(Class<?> rec, boolean requiredByDefault, String where) {
    requireRecord(rec, where);
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    List<String> required = new ArrayList<>();
    ObjectNode props = mapper.createObjectNode();
    for (RecordComponent rc : rec.getRecordComponents()) {
      SparkParam p = rc.getAnnotation(SparkParam.class);
      Type t = unwrap(rc.getGenericType());
      props.set(rc.getName(), typeSchema(t, p, where + "." + rc.getName()));
      if (requiredByDefault && !isNullable(rc)) {
        required.add(rc.getName());
      }
    }
    if (!required.isEmpty()) {
      ArrayNode req = schema.putArray("required");
      required.forEach(req::add);
    }
    schema.set("properties", props);
    return schema;
  }

  /** 类型映射表；p 可为 null（输出组件未标注）。 */
  private ObjectNode typeSchema(Type t, SparkParam p, String where) {
    ObjectNode n = mapper.createObjectNode();
    if (t instanceof ParameterizedType pt && pt.getRawType() == List.class) {
      n.put("type", "array");
      if (p != null && p.minItems() >= 0) {
        n.put("minItems", p.minItems());
      }
      n.set("items", typeSchema(pt.getActualTypeArguments()[0], null, where + "[]"));
      return n;
    }
    if (!(t instanceof Class<?> c)) {
      throw new IllegalStateException("unsupported generic type at " + where + ": " + t);
    }
    if (c == String.class) {
      n.put("type", "string");
      applyString(n, p);
    } else if (c == int.class || c == Integer.class || c == long.class || c == Long.class) {
      n.put("type", "integer");
      if (p != null && p.min() != Long.MIN_VALUE) {
        n.set("minimum", number(p.min()));
      }
      if (p != null && p.max() != Long.MAX_VALUE) {
        n.set("maximum", number(p.max()));
      }
    } else if (c == boolean.class || c == Boolean.class) {
      n.put("type", "boolean");
      if (p != null && !p.constant().isEmpty()) {
        n.put("const", Boolean.parseBoolean(p.constant()));
      }
    } else if (c == BigDecimal.class) {
      n.put("type", "string");
      n.put("pattern", p != null && !p.pattern().isEmpty() ? p.pattern() : MONEY_PATTERN);
    } else if (c == LocalDate.class) {
      n.put("type", "string");
      n.put("format", "date");
    } else if (c == Instant.class || c == OffsetDateTime.class) {
      n.put("type", "string");
      n.put("format", "date-time");
    } else if (c.isEnum()) {
      n.put("type", "string");
      ArrayNode e = n.putArray("enum");
      for (Object k : c.getEnumConstants()) {
        e.add(((Enum<?>) k).name());
      }
    } else if (c.isRecord()) {
      return recordSchema(c, true, where);
    } else {
      throw new IllegalStateException(
          "unsupported type at "
              + where
              + ": "
              + c.getName()
              + " (use record / String / integer / enum / List)");
    }
    return n;
  }

  private static void applyString(ObjectNode n, SparkParam p) {
    if (p == null) {
      return;
    }
    if (p.enums().length > 0) {
      ArrayNode e = n.putArray("enum");
      Arrays.stream(p.enums()).forEach(e::add);
    }
    if (p.minLength() >= 0) {
      n.put("minLength", p.minLength());
    }
    if (p.maxLength() >= 0) {
      n.put("maxLength", p.maxLength());
    }
    if (!p.pattern().isEmpty()) {
      n.put("pattern", p.pattern());
    } else if (p.format() == ParamFormat.MONEY) {
      n.put("pattern", MONEY_PATTERN);
    }
    if (p.format() == ParamFormat.DATE) {
      n.put("format", "date");
    } else if (p.format() == ParamFormat.DATE_TIME) {
      n.put("format", "date-time");
    }
    if (!p.constant().isEmpty()) {
      n.put("const", p.constant());
    }
  }

  private static ToolMetaRegistry.ParamMeta paramMeta(
      String name, SparkParam p, SparkDefault def, Type t) {
    Map<String, String> aliases = new LinkedHashMap<>();
    for (String a : p.aliases()) {
      int eq = a.indexOf('=');
      if (eq <= 0) {
        throw new IllegalStateException("@SparkParam.aliases entry must be VALUE=别名: " + a);
      }
      aliases.put(a.substring(eq + 1), a.substring(0, eq));
    }
    boolean integer = t == int.class || t == Integer.class || t == long.class || t == Long.class;
    return new ToolMetaRegistry.ParamMeta(
        name,
        p.entity() == null ? EntityType.NONE : p.entity(),
        aliases,
        Arrays.asList(p.unit()),
        p.min() == Long.MIN_VALUE ? null : p.min(),
        p.max() == Long.MAX_VALUE ? null : p.max(),
        p.format(),
        def == null ? null : def.value(),
        integer);
  }

  private JsonNode literal(String value, Type t) {
    if (t == int.class || t == Integer.class || t == long.class || t == Long.class) {
      return number(Long.parseLong(value));
    }
    if (t == boolean.class || t == Boolean.class) {
      return mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
    }
    return mapper.getNodeFactory().textNode(value);
  }

  /** 整数节点：能放进 int 就用 IntNode（与 JSON 解析结果一致，避免 IntNode / LongNode 不等）。 */
  private JsonNode number(long v) {
    return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE
        ? mapper.getNodeFactory().numberNode((int) v)
        : mapper.getNodeFactory().numberNode(v);
  }

  /** Optional&lt;T&gt; → T。 */
  private static Type unwrap(Type t) {
    if (t instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
      return pt.getActualTypeArguments()[0];
    }
    return t;
  }

  /** Optional 或任一名为 Nullable 的注解 → 可空（不进 required；序列化 null 省略）。 */
  static boolean isNullable(RecordComponent rc) {
    if (rc.getType() == Optional.class) {
      return true;
    }
    return Arrays.stream(rc.getAnnotations())
        .anyMatch(a -> a.annotationType().getSimpleName().equals("Nullable"));
  }

  private static void requireRecord(Class<?> c, String where) {
    if (!c.isRecord()) {
      throw new IllegalStateException(where + " must be a record, got " + c.getName());
    }
  }

  private static String lower(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }
}
