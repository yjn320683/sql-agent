package com.yjn.sqlagent.realtime.common;

/** 由宿主 backend 提供登录用户，实时模块不自行维护第二套认证。 */
public interface RealtimeActorProvider {

    String requireActor();
}
