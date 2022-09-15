package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//因为这个是我们手动new出来的，不在容器里，用不了自动装配，和传统写法一样，构造函数自己注入
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    //拦截器那边是在MvcConfig里面配置的所以，可以用自动装配，那边在装配完，在传过来这边的构造函数
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override //这里是验证登录状态，从用token去Redis里面校验 那个用户信息，所以先从网页发过来的请求头去获取 token，然后用token去找Redis里面的用户信息
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){//工具类里面的是否为空

            return true;
        }

        //2.获取session中的用户
        //        Object user = session.getAttribute("user");
        //2.基于token获取redis中的用户  因为之前存的就是HashMap结构的，所以取的时候，也是HashMap结构的
        //3.所以用entries可以返回一个Map给我们 ,当然里面的Key也是要和存的时候一样的
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);


        //3.判断Redis中用户是否存在
        if (userMap.isEmpty()){

            return true;
        }

        //5.因为没有自动转换，所以要手动把在Redis查询到的HashMap信息转为UserDTO对象，因为查出来后要返回后端进行判断用户是否存在，那要转成java对象
        //这里也是工具类的使用 ，  用userMap去填充 UserDTO，false表示不忽略转换过程中的错误
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);


        //6.存在，保存用户到ThreadLocal 也是用工具包里面写好的类  可以去查找一下ThreadLocal ，作用这个是每个单独给一个线程
        UserHolder.saveUser(userDTO);

        //7.刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //8.放行
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户，防止内存泄漏，就是对象一直占用内存，导致内存可用的量不断减少
        UserHolder.removeUser();
    }
}
