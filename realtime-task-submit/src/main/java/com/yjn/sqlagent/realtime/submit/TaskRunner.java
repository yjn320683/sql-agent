package com.yjn.sqlagent.realtime.submit;

import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import com.yjn.sqlagent.realtime.common.DebugReport;

interface TaskRunner {
    DebugReport validate(SubmissionSpec spec) throws Exception;
    void execute(SubmissionSpec spec) throws Exception;
}
