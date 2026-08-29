package com.yjn.sqlagent.realtime.common;

public class RealtimeResponse<T> {
    private int code;
    private String message;
    private T data;

    public RealtimeResponse() {
    }

    private RealtimeResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> RealtimeResponse<T> success(T data) {
        return new RealtimeResponse<>(0, "success", data);
    }

    public static <T> RealtimeResponse<T> failure(int code, String message) {
        return new RealtimeResponse<>(code, message, null);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
