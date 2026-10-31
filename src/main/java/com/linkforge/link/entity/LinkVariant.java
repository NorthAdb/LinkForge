package com.linkforge.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One weighted target of an A/B split link (D-C3-2); lifecycle is hard
 * delete + insert, so unlike ShortLink there is no logical-delete column. */
@Data
@TableName("lf_link_variant")
public class LinkVariant {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long linkId;
    /** Case-sensitive (V10 pins utf8mb4_0900_bin); [A-Za-z0-9_-]{1,32}. */
    private String variantKey;
    private String targetUrl;
    private Integer weight;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
