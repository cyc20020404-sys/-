package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Holder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
@Resource
private StringRedisTemplate stringRedisTemplate;
@Resource
private IUserService userService;
    @Override
    public Result follow(Long followUserId, boolean isFollow) {
        //获取当前用户
        Long userid = UserHolder.getUser().getId();
        //1.判断关注还是取关
        if(isFollow){
            //2。关注，新增数据
            Follow follow=new Follow();
            follow.setUserId(userid);
            follow.setFollowUserId(followUserId);
            boolean issuccess = save(follow);
            if (issuccess){
                stringRedisTemplate.opsForSet().add("follow:"+userid,followUserId.toString());
            }
        }else {
            //3。取关，删除 delete from tb_follow where user_id=? and follow_user_id=?
            boolean remove = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userid)
                    .eq("follow_user_id", followUserId));
            if (remove) {
                stringRedisTemplate.opsForSet().remove("follow:" + userid, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isfollow(Long followUserId) {
        //获取当前用户
        Long userid = UserHolder.getUser().getId();
        //2.查询是否关注   select from tb_follow where user_id=? and follow_user_id=?
        Integer count = query().eq("user_id", userid).eq("follow_user_id", followUserId).count();
        //3.判断
        return Result.ok(count>0);
    }

    @Override
    public Result followcommon(Long id) {
        //1.获取当前用户
        Long userid = UserHolder.getUser().getId();
        //2.求交集
        String Key="follow:"+userid;
        String Key2="follow:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(Key, Key2);
        if (intersect==null||intersect.isEmpty()){
return Result.ok(Collections.emptyList());
        }
        //3.解析id集合
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        //4.查询用户信息
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //5.返回结果
        return Result.ok(users);
    }
}
