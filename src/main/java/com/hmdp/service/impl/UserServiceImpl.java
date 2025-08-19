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
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendcode(String phone, HttpSession session) {
        //1校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合则返回错误信息
            return Result.fail("手机号格式错误");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到Redis
       stringRedisTemplate.opsForValue().set("login:code:"+phone,code,2, TimeUnit.MINUTES);
        //发送验证码
        log.info("发送的验证码为；{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不一致，报错
            return Result.fail("验证码错误");
        }

        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user =  creatUserWithphone(phone);
        }

        // 7.保存用户信息到 redis中
        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8.返回token
        return Result.ok(token);
    }

    /**
     * 签到功能
     * @return
     */
    @Override
    public Result sign() {
        //获取当前用户
        Long userid = UserHolder.getUser().getId();
        // 2. 构造签到键（格式：sign:用户ID）
        LocalDate now = LocalDate.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userid + keySuffix; // 如 sign:1001:202201
        // 4. 执行签到（setBit返回旧值：false表示未签到过，true表示已签到过）
        int DAYofMonth=now.getDayOfMonth();
        boolean oldValue = stringRedisTemplate.opsForValue().setBit(key, DAYofMonth-1, true);
        return Result.ok();
    }

    @Override
    public Result signcount() {
        //获取当前用户
        Long userid = UserHolder.getUser().getId();
        //用户当前日期
        LocalDateTime now = LocalDateTime.now();
        //拼接KEY
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String Key="signcount"+userid+keySuffix;
        //获取截至当天本月签到数据
        Integer DayofMonth=now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(Key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(DayofMonth))
                        .valueAt(0));
        //判断连续签到天数
        Long num = result.get(0);
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            num = num >>> 1; // 修正：将右移结果重新赋值给 num
        }
        return Result.ok(count);
    }

    private User creatUserWithphone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName("User"+ RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
