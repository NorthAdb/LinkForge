package com.linkforge.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.user.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * Batch 2b guard serialization: member writes that can shrink the ADMIN set
     * (demote / disable / remove) lock the caller's tenant row inside their
     * transaction, so two ADMINs concurrently demoting each other queue on the
     * row lock and the second one re-reads a world where it is the last ADMIN
     * and is refused (plan self-audit A4). A raw @Select bypasses the
     * MyBatis-Plus logic-delete handling, hence the explicit deleted = 0.
     */
    @Select("SELECT * FROM lf_tenant WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Tenant selectForUpdate(@Param("id") Long id);
}
