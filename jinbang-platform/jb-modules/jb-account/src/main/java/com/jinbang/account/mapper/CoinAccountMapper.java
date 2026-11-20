package com.jinbang.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jinbang.account.domain.CoinAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 余额变更一律用条件更新，靠数据库行锁保证并发安全，不做「先读后写」。 */
@Mapper
public interface CoinAccountMapper extends BaseMapper<CoinAccount> {

    @Select("SELECT * FROM coin_account WHERE user_id = #{userId}")
    CoinAccount selectByUser(@Param("userId") Long userId);

    @Update("UPDATE coin_account SET balance = balance + #{amt}, total_income = total_income + #{amt}, "
            + "updated_at = NOW() WHERE user_id = #{userId}")
    int income(@Param("userId") Long userId, @Param("amt") long amount);

    @Update("UPDATE coin_account SET balance = balance - #{amt}, total_spent = total_spent + #{amt}, "
            + "updated_at = NOW() WHERE user_id = #{userId} AND balance >= #{amt}")
    int spend(@Param("userId") Long userId, @Param("amt") long amount);

    @Update("UPDATE coin_account SET balance = balance - #{amt}, frozen = frozen + #{amt}, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND balance >= #{amt}")
    int freeze(@Param("userId") Long userId, @Param("amt") long amount);

    /** 结算：解除全部冻结额，其中 charge 记为支出，其余退回余额。 */
    @Update("UPDATE coin_account SET frozen = frozen - #{frozenAmt}, balance = balance + #{frozenAmt} - #{charge}, "
            + "total_spent = total_spent + #{charge}, updated_at = NOW() WHERE user_id = #{userId} AND frozen >= #{frozenAmt}")
    int settle(@Param("userId") Long userId, @Param("frozenAmt") long frozenAmount, @Param("charge") long charge);

    @Update("UPDATE coin_account SET exp = exp + #{amt}, updated_at = NOW() WHERE user_id = #{userId}")
    int addExp(@Param("userId") Long userId, @Param("amt") long amount);
}
