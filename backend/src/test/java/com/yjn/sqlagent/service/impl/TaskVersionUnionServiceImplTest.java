package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yjn.sqlagent.datacompare.service.HiveDdlService;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.dto.TaskVersionUnionMemberDTO;
import com.yjn.sqlagent.model.dto.TaskVersionUnionSaveDTO;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TaskVersionUnionServiceImplTest {

    @Test
    void rejectsTwoVersionsFromTheSameTaskBeforeWriting() {
        TaskVersionUnionSaveDTO request = request(member(7L, 2, null), member(7L, 3, null));

        assertThrows(BusinessException.class, () -> service().create("tester", request));
    }

    @Test
    void rejectsCrossDdlConflictBeforeWriting() {
        TaskVersionUnionSaveDTO request = request(
                member(7L, 2, "ALTER TABLE dw.orders ADD COLUMNS (a STRING)"),
                member(8L, 2, "ALTER TABLE dw.orders CHANGE COLUMN a a BIGINT"));

        assertThrows(IllegalArgumentException.class, () -> service().create("tester", request));
    }

    @Test
    void rejectsDestructiveDdlBeforeWriting() {
        TaskVersionUnionSaveDTO request = request(
                member(7L, 2, "DROP TABLE dw.orders"), member(8L, 2, null));

        assertThrows(IllegalArgumentException.class, () -> service().create("tester", request));
    }

    private TaskVersionUnionServiceImpl service() {
        return new TaskVersionUnionServiceImpl(
                null, null, null, null, new HiveDdlService(), null, null);
    }

    private TaskVersionUnionSaveDTO request(TaskVersionUnionMemberDTO... members) {
        TaskVersionUnionSaveDTO request = new TaskVersionUnionSaveDTO();
        request.setMembers(Arrays.asList(members));
        return request;
    }

    private TaskVersionUnionMemberDTO member(long taskId, int versionNo, String ddl) {
        TaskVersionUnionMemberDTO member = new TaskVersionUnionMemberDTO();
        member.setTaskId(taskId);
        member.setVersionNo(versionNo);
        member.setVersionRevision(1L);
        member.setDdl(ddl);
        return member;
    }
}
