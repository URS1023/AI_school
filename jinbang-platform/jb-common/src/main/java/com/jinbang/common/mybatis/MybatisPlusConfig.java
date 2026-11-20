package com.jinbang.common.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.jinbang.common.core.TenantEntity;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.tenant.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(TenantContext.currentTenantId());
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            /**
             * 只有实体继承了 {@link TenantEntity} 的表才拼租户条件。
             * 判断依据是实体类型而不是手写白名单：新增学校级表时不会因为忘了登记而漏隔离（R7）。
             */
            @Override
            public boolean ignoreTable(String tableName) {
                if (TenantContext.isIgnored()) {
                    return true;
                }
                TableInfo info = TableInfoHelper.getTableInfo(tableName);
                return info == null || !TenantEntity.class.isAssignableFrom(info.getEntityType());
            }
        }));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(DbType.MYSQL);
        page.setMaxLimit(500L);
        interceptor.addInnerInterceptor(page);
        return interceptor;
    }

    @Bean
    public MetaObjectHandler auditFieldFiller() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject meta) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(meta, "createdAt", LocalDateTime.class, now);
                strictInsertFill(meta, "updatedAt", LocalDateTime.class, now);
                LoginHelper.current().map(CurrentIdentity::userId)
                        .ifPresent(uid -> strictInsertFill(meta, "createdBy", Long.class, uid));
                if (meta.hasSetter("tenantId") && getFieldValByName("tenantId", meta) == null) {
                    long tenant = TenantContext.currentTenantId();
                    if (tenant != TenantContext.NO_TENANT) {
                        setFieldValByName("tenantId", tenant, meta);
                    }
                }
            }

            @Override
            public void updateFill(MetaObject meta) {
                setFieldValByName("updatedAt", LocalDateTime.now(), meta);
            }
        };
    }
}
