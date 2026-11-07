package com.linkforge.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** Member list row. {@code role} is the effective role: a stored NULL reads
 * as MEMBER (the read tolerance the V12 backfill leaves in place). */
@Data
@AllArgsConstructor
public class MemberView {
    private Long id;
    private String username;
    private String role;
    private Integer status;
    private LocalDateTime createdAt;
}
