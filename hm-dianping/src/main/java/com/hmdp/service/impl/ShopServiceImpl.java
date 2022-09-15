package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource//用来访问redis要用的，里面封装了访问的方法
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result querygetById(Long id) {

        String key = CACHE_SHOP_KEY + id;
        //1.从redis缓存查询商户信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            //反序列化成java对象才能返回到前端  这里提供工具类来完成反序列化
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //因为上面isNotBlank只有有数据才会执行 所以把下面我们给可能是恶意访问的，导致两个null的就将空值写入redis而且设置成空字符串，等会它再次访问就
        //从redis里面返回这个空串" " 给它，因为下面就是访问数据库了，所以在这就就给拦截下来，如果为空就让他过（就是还没设置拦截的，一会就给它拦截了）
        if (shopJson != null) {//这里就命中了我们给他在redis设置的那个空串了
            //返回一个错误信息
            return Result.fail("店铺信息不存在！");
        }




        //4.不存在，根据id查询数据库 这里用的是正常的sql的查询方法了，当然mybatis-plus会帮你写出来sql语句
        Shop shop = getById(id);

        //5.数据库中也没有，就返回错误
        if (shop == null) {
            //将空值写入Redis
            //返回错误信息
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }

        //6.数据库中有，那就先写到缓存里面
        //同样要序列化成JSON字符串才能存进去
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7.返回数据

        return Result.ok(shop);
    }

    //获取锁                               注意，锁的key和缓存key不是同一个
    private boolean tryLock(String key){//这里是获取锁的，并且判断这个key是否存在，如果不存在就写入
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//这里用工具类拆箱
    }

    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);//释放锁，删除
    }









    @Override//这个模块不是面向用户的客户端，是店家后台管理端的
    @Transactional //防止删除过程中抛异常，那么更新数据库也要回滚，所以可以通过事务控制原子性，而且这里是单体式系统
    public Result update(Shop shop) {//如果是分布式系统，删除缓存可能由另外 一个系统操作，这里就要通过MQ去异步的通知对方完成删除，那要借助TTC

        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //前面已经选择出结果，先更新数据库与删除缓存
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return null;
    }
}
