package com.hmdp.service.impl;

import cn.hutool.core.lang.Holder;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    /**
     * 查询blog
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogliked(blog);
        return Result.ok(blog);
    }

    public void isBlogliked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，默认未点赞
            return ;
        }
        String KEY = "blog::liked" + blog.getId();
        Long userid = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(KEY, userid.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogliked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean issuccess = save(blog);
        if (!issuccess){
            return Result.fail("新增笔记失败");
        }
        //3.查询笔记作者所有粉丝   select *from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4.推送笔记id给所有粉丝
        //4.1获取粉丝id
        for (Follow follow:follows){
            Long userid = follow.getId();
            //4.2推送
            String Key="feed:"+userid;
            stringRedisTemplate.opsForZSet().add(Key,blog.getId().toString(),System.currentTimeMillis());
        }

        return null;
    }

    @Override
    public Result queryblogoffollowm(long max, Integer offset) {
        //1.获取用户id
        Long userid = UserHolder.getUser().getId();
        //2.滚动查询
        String Key="feed:"+userid;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                .opsForZSet().rangeByScoreWithScores(Key, 0, max, offset, 3);
        //3.非空判断
        if (typedTuples==null){
            return Result.ok("没有新的博客");
        }
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long mintime=0;
        long os=1;
        //4.解析数据
        for (ZSetOperations.TypedTuple<String> typetupl:typedTuples) {
            //获取id
            String value = typetupl.getValue();
            ids.add(Long.valueOf(value));
            //读取分数
            Long time = typetupl.getScore().longValue();
            if (time == mintime) {
                os++;
            } else {
                mintime = time;
                os = 1;
            }
        }
            //5.根据id查询blog
            String idStr = StrUtil.join(",", ids);
            List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
            for (Blog blog : blogs) {
                Long userId = blog.getUserId();
                User user = userService.getById(userId);
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
                isBlogliked(blog);
            }
            //6.封装返回
            ScrollResult r = new ScrollResult();
            r.setMinTime(mintime);
            r.setOffset(offset);
            r.setList(blogs);
            return Result.ok(r);
        }

    @Override
    public Result likeBlig(Long id) {

        //1.获取登录用户
        Long userid = UserHolder.getUser().getId();
        String KEY = "blog::liked" + id;
        Double score = stringRedisTemplate.opsForZSet().score(KEY, userid.toString());
        //2.判断当前用户是否点过赞
        if (score == null) {
            //3。未点过赞
            //3.1 修改数据库点赞数量加一
            boolean updateResult = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2写入redis set集合1
            if (updateResult) {
                stringRedisTemplate.opsForZSet().add(KEY, userid.toString(), System.currentTimeMillis());
            }
        } else {
            //4已经点赞
            //4.1取消点赞，修改数据库减一
            boolean updateResult = update().setSql("liked=liked-1").eq("id", id).update();
            //4.2移除redis set集合
            if (updateResult) {
                stringRedisTemplate.opsForZSet().remove(KEY, userid.toString());
            }
        }
        return Result.ok();
    }
}
