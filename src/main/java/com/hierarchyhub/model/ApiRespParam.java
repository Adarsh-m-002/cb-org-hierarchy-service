package com.hierarchyhub.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ApiRespParam {

    private String resmsgid;
    private String msgid;
    private String err;
    private String status;
    private String errmsg;

    public ApiRespParam(String id) {
        resmsgid = id;
        msgid = id;
    }
}
