package com.linkforge.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.user.entity.TenantInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TenantInvitationMapper extends BaseMapper<TenantInvitation> {

    /**
     * The single-use claim: marks the invitation accepted only if it is still
     * unconsumed, unrevoked and unexpired at the moment of the UPDATE. A return
     * of 0 means the race was lost (or the row changed state since it was read)
     * and the caller must treat the token as invalid — inside the accept
     * transaction this also rolls back the just-created user row, so a lost
     * race never leaves an orphan account behind.
     */
    @Update("UPDATE lf_tenant_invitation SET accepted_by = #{userId}, accepted_at = #{now} "
            + "WHERE id = #{id} AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > #{now}")
    int accept(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);
}
