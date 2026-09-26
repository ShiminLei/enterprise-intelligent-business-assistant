package com.enterprise.assistant.common;

/** 统一错误响应，契约见 contracts/openapi.yaml 的 Error。 */
public record ApiError(String code, String message) {

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message);
    }
}
