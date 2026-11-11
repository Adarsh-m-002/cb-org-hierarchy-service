package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One failed child organisation in a {@code POST /link} batch. Matches the Sunbird envelope error naming standard
 * ({@code err}/{@code errmsg}) so a client can bind {@code error.childOrgId}/{@code error.err}/{@code error.errmsg}
 * directly. No {@code success} flag: this list only ever holds failures.
 */
@Getter
@AllArgsConstructor
public class OrgLinkErrorDetail {
    private final String childOrgId;
    private final String err;
    private final String errmsg;
}
