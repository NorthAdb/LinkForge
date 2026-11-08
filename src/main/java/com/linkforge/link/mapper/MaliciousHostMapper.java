package com.linkforge.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.link.entity.MaliciousHost;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MaliciousHostMapper extends BaseMapper<MaliciousHost> {

    /**
     * Feed upsert: a host seen again only advances {@code last_seen} (and keeps
     * its original {@code first_seen}); hosts absent from this batch are left
     * untouched — the sync never deletes (no automatic delisting).
     */
    @Insert("<script>"
            + "INSERT INTO lf_malicious_host (host, source, first_seen, last_seen) VALUES "
            + "<foreach collection='hosts' item='h' separator=','>"
            + "(#{h}, #{source}, #{now}, #{now})"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE last_seen = #{now}"
            + "</script>")
    int upsertBatch(@Param("hosts") List<String> hosts,
                    @Param("source") String source,
                    @Param("now") LocalDateTime now);

    @Select("SELECT host FROM lf_malicious_host")
    List<String> selectAllHosts();
}
