package com.linkforge.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
