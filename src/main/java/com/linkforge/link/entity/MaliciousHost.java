package com.linkforge.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Batch 3b: one host imported from the malicious-host feed (V14). Rows are
 * never deleted by the sync: a host that leaves the feed keeps its row and
 * its {@code lastSeen} simply stops advancing (no automatic delisting).
 */
@Data
@TableName("lf_malicious_host")
public class MaliciousHost {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** Normalized host: IDN ASCII, lowercase, no trailing dot (TargetPolicy pipeline). */
    private String host;
    /** Feed source tag, e.g. "urlhaus". */
    private String source;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;
}
