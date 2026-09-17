package com.yjn.sqlagent.datamap.graph;

public class GraphStoreUnavailableException extends RuntimeException {
    public GraphStoreUnavailableException(String message) { super(message); }
    public GraphStoreUnavailableException(String message, Throwable cause) { super(message, cause); }
}
