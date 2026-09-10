package com.sparkrooter.contracts.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 契约 action-request：前端 → Runtime 确认动作。formData 只允许标量值，键名白名单由 Runtime 按当前屏 Form.props.fields[] 校验。
 */
public record ActionRequest(
    @NotBlank String confirmationToken, @NotNull Map<String, Object> formData) {}
