package com.jinbang.common.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.R;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理：统一错误码与友好提示，禁止把「数据库连接」等内部信息返回给用户（PRD 13）。
 * 无权限统一 403 并给出申请指引（PRD 2.2 ⚠️、R9、AC-13/AC-16）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> notLogin(NotLoginException e) {
        return R.fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Map<String, String>> forbidden(Exception e) {
        R<Map<String, String>> r = R.fail(ErrorCode.FORBIDDEN);
        r.setData(Map.of("guide", "游客教师可在「个人中心 → 加入学校」输入邀请码申请；学校教师请联系本校管理员分配角色"));
        return r;
    }

    @ExceptionHandler(BizException.class)
    public R<Object> biz(BizException e) {
        R<Object> r = R.fail(e.getCode(), e.getMessage());
        r.setData(e.getDetail());
        return r;
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> bind(BindException e) {
        String msg = e.getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return R.fail(ErrorCode.BAD_REQUEST.code(), msg.isEmpty() ? ErrorCode.BAD_REQUEST.msg() : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> violation(ConstraintViolationException e) {
        return R.fail(ErrorCode.BAD_REQUEST.code(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> badRequest(Exception e) {
        return R.fail(ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public R<Void> duplicate(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMostSpecificCause().getMessage());
        return R.fail(ErrorCode.CONFLICT.code(), "数据已存在，请勿重复提交");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> notFound(NoResourceFoundException e) {
        return R.fail(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> unknown(Exception e) {
        log.error("未处理异常", e);
        return R.fail(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
