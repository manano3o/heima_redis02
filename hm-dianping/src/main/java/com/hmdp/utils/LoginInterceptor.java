package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//因为这个是我们手动new出来的，不在容器里，用不了自动装配，和传统写法一样，构造函数自己注入
public class LoginInterceptor implements HandlerInterceptor {

    //这里不需要stringTemp……了，因为拦截，全部放在刷新那部分代码量里面了

    @Override //这里是验证登录状态，从用token去Redis里面校验 那个用户信息，所以先从网页发过来的请求头去获取 token，然后用token去找Redis里面的用户信息
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null){
            //没有，需要拦截。设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }

        //有用户，则放行
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户，防止内存泄漏，就是对象一直占用内存，导致内存可用的量不断减少
        UserHolder.removeUser();
    }
}
