package com.linkforge.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    /**
     * Locking current read of one user row — the serialization point of the
     * whole credential lifecycle (audit M1): every mint/revoke path takes this
     * lock (after the tenant row lock, where one applies) before it reads or
     * writes credential state, so a revocation can never be overtaken by an
     * in-flight rotation of the same user. A raw {@code @Select} bypasses the
     * MyBatis-Plus logic-delete handling, so a logically deleted row CAN come
     * back here — callers must treat {@code deleted = 1} as absent.
     */
    @Select("SELECT * FROM lf_user WHERE id = #{id} FOR UPDATE")
    UserAccount selectForUpdate(@Param("id") Long id);

    /**
     * Atomic token_version increment (audit M1): replaces the read-modify-write
     * bump, which could lose a concurrent increment and — being a full-row
     * update — overwrite a concurrent password/role/status write with a stale
     * entity. token_version is NOT NULL DEFAULT 0 (V11), so the plain +1 is safe.
     */
    @Update("UPDATE lf_user SET token_version = token_version + 1, updated_at = NOW() WHERE id = #{id}")
    int incrementTokenVersion(@Param("id") Long id);

    /**
     * The tenant's active ADMIN ids as a locking current read (audit M2): the
     * last-ADMIN guard counts THESE rows, never a REPEATABLE READ snapshot
     * taken before the tenant row lock. Locking the member rows themselves
     * (not a COUNT aggregate) is what makes the count a current read.
     */
    @Select("SELECT id FROM lf_user WHERE tenant_id = #{tenantId} AND tenant_role = 'ADMIN' "
            + "AND status = 1 AND deleted = 0 FOR UPDATE")
    List<Long> selectActiveAdminIdsForUpdate(@Param("tenantId") Long tenantId);
}
