package com.yjn.sqlagent.datamap.project;

/** 为数据地图补齐尚未生成不可变血缘快照的历史任务。 */
public interface LineageSnapshotBootstrapper {
    int backfill(int limit);
}
