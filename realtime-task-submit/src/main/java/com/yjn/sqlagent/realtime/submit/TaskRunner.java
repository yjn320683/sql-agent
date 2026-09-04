package com.yjn.sqlagent.realtime.submit;

import com.yjn.sqlagent.realtime.common.SubmissionSpec;

interface TaskRunner {
    void validate(SubmissionSpec spec) throws Exception;
    void execute(SubmissionSpec spec) throws Exception;
}
