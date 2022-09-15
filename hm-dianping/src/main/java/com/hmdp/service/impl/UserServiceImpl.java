package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource//手动注入SpringDataAPI 因为我们构建项目的时候，已经做完了对Redis的配置，所以直接拿来用了
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){

            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误……");
        }
        //3.符合，生成验证码

        String code = RandomUtil.randomNumbers(6);
        //4，保存验证码到Redis  而且要设置有效期，不然redis一直被占用  然后这里的  login:code 和  2 都要被定义成常量，这里写在Utils里的RedisConstants
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        session.setAttribute("code",code);
        //5，发送验证码  这个暂时不做，因为要调用第三方的平台，阿里云之类的
        //验证码在公司有独立的服务直接调用就行
        //下面是假设发送成功拿个日志记录一下
        log.debug("发送短信验证码成功，验证码： {}",code);

        //返回ok

        return Result.ok();
        //返回值result是我们dto包下自定义的，代表业务结果
    }

    @Override//登录注册合二为一  每次请求都要做独立的验证，因为你不知道第二次请求是否是有误的，所以在检测验证码之前还要再检测一次手机号
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.验证手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){

            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误……");
        }


        //3.取出刚刚生成并保存在Redis里面的验证码
//        Object cacheCode = session.getAttribute("code"); 这里取的方法get里，就是前面存验证码用的key 而且是用String类型存进去的
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        //再从loginForm取出用户提交上来的验证码，看是否正确
        String code = loginForm.getCode();
        //转成字符串匹配    所以这里不用再转成toString，转成String类型了，因为取出来的就是String
        if (cacheCode == null || !cacheCode.equals(code)){

            //如果不一致，报错
            return Result.fail("验证码错误……");
        }

        //4.一致，去数据库把这个用户查出来 .one表示查找返回一个
        User user = query().eq("phone", phone).one();


        //5.判断用户是否存在
        if (user == null){

            //6.没有该用户，创建新的用户并且保存在数据库里面
           user = createUserWithPhone(phone);

        }

        //7.无论是否有用户（没有就帮他注册），最后为了后面的业务，都要把用户信息放进session里面一直带着
        //把user导入进去，命名为user（双引号） 所以这里存进去session的就是DTO了
        //7.1保存用户信息到Redis  即用随机生成的token，作为登录令牌  用 Hutools里的不用自带的
        String token = UUID.randomUUID().toString(true);

        //7.2 以token为key将User对象转为Hash存储  实际上可以用HashMap ,然后这里的拷贝没见过，是为了过滤数据
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //这里用BeanUtil去把 Bean对象直接转成 HashMap
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)//忽略空值
                        .setFieldValueEditor((fieldName,fieldValue)-> fieldValue.toString())
                //这里因为redis里面要求存进去的是String，String类型，所以虽然这里好像有点点麻烦，就可以把那个UserDTO里面的id 是long的问题解决
                //这个可以自定义设置类型 所以可以转成String

                );

        //7.3 存储 用Hash类型  为了一次性的把UserDTO里的字段保存，用putAll ,putAll里面用 token作为 key，userMap作为value，
        // 实际上用put，是一个token，后是hash的key和value，但是这个一次只能存一个k-v数据
        //还有这个也要设置有效期，可以参考Session  30分钟
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);

        //7.4 设置token有效期 但是这个有效期是 不管你访问不访问，只要过了30分钟，就把用户信息从内存中清理掉
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //8 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        //2.保存用户
        save(user);
        return user;
    }


}
