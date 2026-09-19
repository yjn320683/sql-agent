package com.yjn.sqlagent.datamap.service;

/** 数据地图后台操作与已有运行冲突。 */
public class DataMapOperationConflictException extends RuntimeException {
    public DataMapOperationConflictException(String message) {
        super(message);
    }
}
