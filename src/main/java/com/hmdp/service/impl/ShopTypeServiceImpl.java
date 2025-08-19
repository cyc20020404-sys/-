package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
@Resource
private StringRedisTemplate stringRedisTemplate;

    public Result queryTypeList() {
        String key = "shop:types:list";
        //查询REDIS是否存在商铺类型信息
     String typeListJson= stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(typeListJson)){
            // 存在，将 JSON 字符串反序列化为 List<ShopType>
            List<ShopType> typeList = JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(typeList);
        }
        //不存在，则去数据库中查询
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if(typeList==null){
            //数据库中不存在，返回错误
            return Result.fail("商铺类型不存在");
        }
        //数据库中存在
        //把数据存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));
        //返回
        return Result.ok(typeList);
    }


}
