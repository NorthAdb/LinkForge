package com.linkforge.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.auth.RefreshToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    /**
     * Locking current read of the presented token's row (audit M1): inside the
     * refresh transaction the row is re-read FOR UPDATE after the owning
     * user's row lock is held, so the revoked/rotated state validated here is
     * the state the rotation then acts on — no read/act gap.
     */
    @Select("SELECT * FROM lf_refresh_token WHERE token_hash = #{tokenHash} FOR UPDATE")
    RefreshToken selectByHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * The rotation itself: a single conditional UPDATE so two concurrent refreshes
     * of the same token cannot both succeed — the loser updates 0 rows and is
     * handled by the reuse path (grace or family kill) after re-reading the row.
     */
    @Update("UPDATE lf_refresh_token SET revoked_at = #{now}, revoke_cause = 'ROTATED' "
            + "WHERE id = #{id} AND revoked_at IS NULL")
    int rotate(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Revokes every still-live row of one family (logout, reuse kill). */
    @Update("UPDATE lf_refresh_token SET revoked_at = #{now}, revoke_cause = #{cause} "
            + "WHERE family_id = #{familyId} AND revoked_at IS NULL")
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("cause") String cause, @Param("now") LocalDateTime now);

    /** Revokes every still-live refresh row of a user (logout-all, password change). */
    @Update("UPDATE lf_refresh_token SET revoked_at = #{now}, revoke_cause = #{cause} "
            + "WHERE user_id = #{userId} AND revoked_at IS NULL")
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("cause") String cause, @Param("now") LocalDateTime now);
}
