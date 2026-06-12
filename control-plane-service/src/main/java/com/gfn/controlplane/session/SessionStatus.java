package com.gfn.controlplane.session;

public enum SessionStatus {
    QUEUED,
    RESERVED,
    STARTING,
    STREAMING,
    TERMINATING,
    TERMINATED,
    EXPIRED,
    FAILED
}

